package co.agrovision.ml.inference

import android.util.Log
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.port.ActiveModelProvider
import co.agrovision.core.model.port.LoadedModel
import co.agrovision.core.model.port.ModelSource
import co.agrovision.core.model.port.PestClassifier
import co.agrovision.core.model.scan.Embedding
import co.agrovision.core.model.scan.RawInference
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.ml.preprocessing.TensorPreprocessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import kotlin.math.ln
import javax.inject.Singleton

/**
 * Ejecución del modelo con LiteRT.
 *
 * ## Tres invariantes que esta clase existe para sostener
 *
 * **Un solo hilo.** Todo entra por `inferenceDispatcher`, que es un despachador
 * dedicado de un hilo. El intérprete de LiteRT **no es seguro para uso
 * concurrente**, y dos clasificaciones simultáneas no producen una excepción de
 * Kotlin: producen un fallo en código nativo, es decir, un cierre inesperado sin
 * traza aprovechable. Serializar el acceso no es una optimización, es la
 * condición para que funcione.
 *
 * **Liberación explícita.** La memoria nativa de LiteRT no la administra el
 * recolector de basura de la JVM. Omitir `release()` produce fugas que en
 * dispositivos de 2 GB terminan en cierre forzado. El ViewModel lo llama en
 * `onCleared()`.
 *
 * **Precalentamiento.** El intérprete se instancia y ejecuta una inferencia con
 * tensor vacío al montar la pantalla de cámara. Sin eso, la primera
 * clasificación real tarda el doble o el triple que la estacionaria — y esa
 * primera es justamente la que forma la impresión del productor sobre si la app
 * sirve o no.
 */
@Singleton
class LiteRtPestClassifier @Inject constructor(
    private val models: ActiveModelProvider,
    private val preprocessor: TensorPreprocessor,
    // ⚠️ El calificador NO es decorativo. Sin él, Dagger inyectaría cualquier
    // `CoroutineDispatcher` disponible —`Dispatchers.IO`, por ejemplo— y el
    // intérprete de LiteRT recibiría llamadas concurrentes. El fallo no sería una
    // excepción de Kotlin sino un cierre en código nativo sin traza aprovechable.
    @Dispatcher(AgroDispatcher.Inference)
    private val inferenceDispatcher: CoroutineDispatcher,
    private val listener: InferenceTelemetryListener,
    // Las compuertas 2 y 3 se arman con la calibración y las etiquetas del modelo
    // activo: sin esto el respaldo del modelo heredado se queda sin etiquetas
    private val gateHolder: ActiveGateHolder,
) : PestClassifier {

    private var session: Session? = null

    // El `: Unit` es explícito a propósito: sin él, Kotlin infiere `Unit?` a
    // partir del `session?.let { … }` final y la firma deja de encajar con el
    // puerto del dominio.
    override suspend fun warmUp(): Unit = withContext(inferenceDispatcher) {
        val existing = session
        val model = models.activeModel() ?: run {
            Log.w(TAG, "No hay modelo activo; el precalentamiento no hace nada.")
            return@withContext
        }

        // Si ya hay una sesión de esta misma versión, no se reconstruye: crear el
        // intérprete es lo caro, y el puntero de ranura solo cambia tras una
        // actualización.
        if (existing != null && existing.version == model.version) {
            gateHolder.update(model)
            return@withContext
        }

        existing?.close()
        session = null

        session = runCatching { Session.create(model) }
            .onFailure { Log.e(TAG, "No se pudo crear el intérprete", it) }
            .getOrNull()

        session?.let { active ->
            gateHolder.update(model)

            // Inferencia con tensor vacío. Fuerza la asignación de los buffers
            // nativos y la compilación del grafo por parte del delegate.
            runCatching { active.runEmpty() }
                .onFailure { Log.w(TAG, "Falló el precalentamiento; el modelo sigue usable", it) }
            listener.onDelegateSelected(model.version, active.accelerator.telemetryName)
        }
    }

    override suspend fun classify(image: RgbImage): RawInference = withContext(inferenceDispatcher) {
        val active = session ?: run {
            warmUpBlocking()
            session
        } ?: error("No hay modelo activo instalado")

        val input = preprocessor.toInputBuffer(
            image = preprocessor.centerCropAndResize(image),
            quantized = active.quantizedInput,
        )

        val startedAt = System.nanoTime()
        val (logits, embedding) = active.run(input)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

        if (elapsedMillis > AgroConstants.Model.LATENCY_BUDGET.inWholeMilliseconds) {
            // No es un error: es la señal de que este dispositivo está fuera del
            // presupuesto de latencia y hay que saberlo por familia de hardware.
            listener.onLatencyBudgetExceeded(
                modelVersion = active.version,
                delegate = active.accelerator.telemetryName,
                elapsedMillis = elapsedMillis,
            )
        }

        RawInference(
            logits = logits,
            embedding = Embedding(embedding),
            modelVersion = active.version,
            latencyMillis = elapsedMillis,
        )
    }

    override fun activeModelVersion(): String? = session?.version

    override fun release() {
        // Se puede llamar desde cualquier hilo (`onCleared()` corre en el
        // principal). `Session.close()` es idempotente y no bloquea.
        session?.close()
        session = null
    }

    private suspend fun warmUpBlocking() = warmUp()

    /**
     * Un intérprete vivo con su delegate y su descripción de tensores.
     *
     * Se encapsula para que el orden de cierre —intérprete primero, delegate
     * después— quede en un solo sitio. Al revés, el intérprete apunta a memoria
     * ya liberada.
     */
    private class Session(
        val version: String,
        val interpreter: Interpreter,
        val accelerator: AcceleratorChoice,
        val quantizedInput: Boolean,
        private val logitsIndex: Int,
        /** -1 en un modelo de una sola salida: no hay embedding. */
        private val embeddingIndex: Int,
        private val logitsSize: Int,
        private val embeddingSize: Int,
        private val logitsType: DataType,
        private val logitsScale: Float,
        private val logitsZeroPoint: Int,
    ) {
        private var closed = false

        private val quantizedLogits: Boolean
            get() = logitsType == DataType.UINT8 || logitsType == DataType.INT8

        fun run(input: ByteBuffer): Pair<FloatArray, FloatArray> {
            val embedding = FloatArray(embeddingSize)
            val floatLogits = FloatArray(if (quantizedLogits) 0 else logitsSize)
            val byteLogits = ByteArray(if (quantizedLogits) logitsSize else 0)

            val outputs = HashMap<Int, Any>()
            outputs[logitsIndex] = if (quantizedLogits) arrayOf(byteLogits) else arrayOf(floatLogits)
            if (embeddingIndex >= 0) outputs[embeddingIndex] = arrayOf(embedding)
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)

            val raw = if (quantizedLogits) dequantize(byteLogits) else floatLogits
            // Un modelo de una sola salida termina en softmax: lo que sale son
            // probabilidades. Se pasan a logits (ln p) para que el resto de la
            // cadena —que trabaja con logits y temperatura— no distinga el caso.
            val logits = if (embeddingIndex < 0) FloatArray(raw.size) { ln(raw[it].coerceAtLeast(MIN_PROBABILITY)) } else raw
            return logits to embedding
        }

        private fun dequantize(bytes: ByteArray): FloatArray = FloatArray(bytes.size) { index ->
            val value = if (logitsType == DataType.UINT8) bytes[index].toInt() and 0xFF else bytes[index].toInt()
            (value - logitsZeroPoint) * logitsScale
        }

        fun runEmpty() {
            val bytes = interpreter.getInputTensor(0).numBytes()
            run(ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder()))
        }

        fun close() {
            if (closed) return
            closed = true
            // Orden obligatorio: intérprete, luego delegate.
            runCatching { interpreter.close() }
            runCatching { accelerator.close() }
        }

        companion object {
            fun create(model: LoadedModel): Session {
                val configured = DelegateStrategy.configure()
                val buffer = model.source.toByteBuffer()

                val interpreter = try {
                    Interpreter(buffer, configured.options)
                } catch (error: Throwable) {
                    // Si falla con delegate, se reintenta en CPU antes de darse
                    // por vencido: un delegate roto no puede dejar sin escaneo al
                    // productor.
                    configured.accelerator.close()
                    Log.w(TAG, "Fallo con ${configured.accelerator.telemetryName}; se cae a CPU", error)
                    val fallback = DelegateStrategy.configure(AcceleratorPreference.CPU_ONLY)
                    return build(model, Interpreter(buffer, fallback.options), fallback.accelerator)
                }

                return build(model, interpreter, configured.accelerator)
            }

            /**
             * Localiza cuál de las dos salidas son los logits y cuál el
             * embedding.
             *
             * No se asume el orden: depende de cómo exportara el pipeline, y
             * confundirlas produce predicciones absurdas sin ningún error. Se
             * distinguen por tamaño — los logits tienen tantos elementos como
             * clases— y, si eso no bastara, por nombre del tensor.
             */
            private fun build(
                model: LoadedModel,
                interpreter: Interpreter,
                accelerator: AcceleratorChoice,
            ): Session {
                // Con una sola salida el modelo es «simple»: solo clasifica. Sin embedding
                // no hay detector fuera de dominio ni agrupación por similitud, y lo
                // sabe la calibración (isSimple); con dos salidas se exige el embedding.
                require(interpreter.outputTensorCount >= 1) {
                    "El modelo ${model.version} no expone ninguna salida."
                }
                require(interpreter.outputTensorCount >= 2 || model.calibration?.isSimple == true) {
                    "El modelo ${model.version} expone una sola salida pero su calibración exige embedding (§7.1)."
                }

                val classCount = model.labels.size
                val sizes = (0 until interpreter.outputTensorCount).map { index ->
                    index to interpreter.getOutputTensor(index).shape().fold(1) { a, b -> a * b }
                }

                val logits = sizes.firstOrNull { it.second == classCount }
                    ?: error(
                        "Ninguna salida de ${model.version} tiene $classCount elementos. " +
                            "El .tflite y el labels.json son de versiones distintas."
                    )
                val embedding = sizes.firstOrNull { it.first != logits.first }
                    ?: if (model.calibration?.isSimple == true) -1 to 0
                    else error("No se encontró la salida de embedding en ${model.version}")
                val logitsTensor = interpreter.getOutputTensor(logits.first)
                val quantization = logitsTensor.quantizationParams()

                return Session(
                    version = model.version,
                    interpreter = interpreter,
                    accelerator = accelerator,
                    quantizedInput = interpreter.getInputTensor(0).dataType() == DataType.UINT8 ||
                        interpreter.getInputTensor(0).dataType() == DataType.INT8,
                    logitsIndex = logits.first,
                    embeddingIndex = embedding.first,
                    logitsSize = logits.second,
                    embeddingSize = embedding.second,
                    logitsType = logitsTensor.dataType(),
                    logitsScale = quantization.scale,
                    logitsZeroPoint = quantization.zeroPoint,
                )
            }

            private fun ModelSource.toByteBuffer(): ByteBuffer = when (this) {
                is ModelSource.InMemory -> buffer
                is ModelSource.MappedFile -> FileInputStream(file).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                }
            }
        }
    }

    private companion object {
        const val TAG = "AgroVision.Inference"
        const val NANOS_PER_MILLI = 1_000_000L

        /** Evita ln(0) cuando una clase sale con probabilidad cero cuantizada. */
        const val MIN_PROBABILITY = 1e-6f
    }
}

/**
 * Telemetría de inferencia (§12.3, §19.3).
 *
 * Se define como puerto en vez de llamar directamente a Crashlytics para que
 * `:ml:inference` siga sin dependencias de red: el aislamiento lo verifica
 * `verifyMlIsolation` en cada compilación.
 */
interface InferenceTelemetryListener {
    fun onDelegateSelected(modelVersion: String, delegate: String)
    fun onLatencyBudgetExceeded(modelVersion: String, delegate: String, elapsedMillis: Long)

    companion object {
        val NoOp = object : InferenceTelemetryListener {
            override fun onDelegateSelected(modelVersion: String, delegate: String) = Unit
            override fun onLatencyBudgetExceeded(
                modelVersion: String,
                delegate: String,
                elapsedMillis: Long,
            ) = Unit
        }
    }
}
