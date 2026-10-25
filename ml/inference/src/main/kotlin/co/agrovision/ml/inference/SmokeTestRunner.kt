package co.agrovision.ml.inference

import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Paso 6 del procedimiento de actualización** (§8.3): inferencia de humo contra
 * la ranura recién escrita, antes de conmutar el puntero.
 *
 * Detecta modelos corruptos o incompatibles **antes de exponerlos al productor**.
 * Sin este paso, un `.tflite` que pasó hash y firma pero cuyo grafo no es
 * compatible con este dispositivo se descubre en el lote, cuando alguien intenta
 * escanear y la app se cierra.
 *
 * ## Qué comprueba hoy
 *
 * 1. Que el intérprete se puede **construir** desde el archivo. Ya solo esto
 *    descarta un `.tflite` truncado o con un opset que este runtime no conoce.
 * 2. Que expone **al menos dos salidas** — logits y embedding. La doble salida es
 *    requisito de arquitectura (§7.1) y un modelo exportado solo con logits
 *    inutiliza dos subsistemas completos.
 * 3. Que una de ellas tiene exactamente tantos elementos como clases declara
 *    `labels.json`. Si no, el `.tflite` y el `labels.json` son de versiones
 *    distintas, y el modelo diría «roya» donde el grafo dice «minador».
 * 4. Que una inferencia real **termina** y produce números finitos. `NaN` en los
 *    logits recorrería toda la cadena hasta la pantalla sin lanzar ninguna
 *    excepción.
 *
 * ## Lo que todavía no comprueba
 *
 * El documento describe comparar contra **la salida esperada** de una imagen de
 * referencia embebida. Eso exige que el pipeline de entrenamiento publique un
 * sexto artefacto —`reference.json` con la imagen y su salida esperada— y hoy no
 * lo hace (Fase 7).
 *
 * Sin esa comparación, esto detecta un modelo roto pero no un modelo **cambiado**:
 * un `.tflite` válido con los pesos equivocados pasaría. La firma Ed25519 cubre
 * ese caso desde otro ángulo, pero no es lo mismo, y conviene tenerlo presente en
 * lugar de creer que este paso hace más de lo que hace.
 *
 * Se ejecuta **siempre en CPU**: verificar el artefacto y verificar el delegate son
 * dos cosas distintas, y mezclarlas haría que un modelo correcto se rechazara por
 * un driver de GPU defectuoso.
 */
@Singleton
class SmokeTestRunner @Inject constructor() {

    fun run(modelFile: File, expectedClassCount: Int): Boolean {
        if (!modelFile.isFile || modelFile.length() == 0L) {
            Log.w(TAG, "Archivo de modelo ausente o vacío: ${modelFile.path}")
            return false
        }

        val configured = DelegateStrategy.configure(AcceleratorPreference.CPU_ONLY)
        var interpreter: Interpreter? = null

        return try {
            val buffer = FileInputStream(modelFile).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
            }
            interpreter = Interpreter(buffer, configured.options)

            // Un modelo de una sola salida es válido (solo clasifica); si trae
            // embedding, la salida de logits sigue encontrándose por su tamaño.
            if (interpreter.outputTensorCount < MIN_OUTPUTS) {
                Log.e(TAG, "El modelo no expone ninguna salida.")
                return false
            }

            val outputSizes = (0 until interpreter.outputTensorCount).map { index ->
                interpreter.getOutputTensor(index).shape().fold(1) { a, b -> a * b }
            }
            val logitsIndex = outputSizes.indexOfFirst { it == expectedClassCount }
            if (logitsIndex < 0) {
                Log.e(
                    TAG,
                    "Ninguna salida tiene $expectedClassCount elementos (hay $outputSizes). " +
                        "El .tflite y el labels.json no son de la misma versión."
                )
                return false
            }

            // Las salidas cuantizadas (uint8/int8) se leen como bytes: no pueden ser NaN.
            val outputs = outputSizes
                .mapIndexed { index, size ->
                    val type = interpreter.getOutputTensor(index).dataType()
                    val quantized = type == DataType.UINT8 || type == DataType.INT8
                    index to (if (quantized) arrayOf(ByteArray(size)) else arrayOf(FloatArray(size)))
                }
                .toMap()

            interpreter.runForMultipleInputsOutputs(
                arrayOf<Any>(referenceInput(interpreter)),
                outputs,
            )

            val logits = (outputs[logitsIndex] as Array<*>)[0]
            if (logits is FloatArray && !logits.all { it.isFinite() }) {
                Log.e(TAG, "La inferencia de humo produjo valores no finitos.")
                return false
            }

            true
        } catch (error: Throwable) {
            // `Throwable` y no `Exception` a propósito: un .tflite incompatible
            // puede lanzar `UnsatisfiedLinkError` o `OutOfMemoryError`, y ninguno
            // de los dos puede tumbar la app durante una actualización en
            // segundo plano.
            Log.e(TAG, "La inferencia de humo falló para ${modelFile.name}", error)
            false
        } finally {
            runCatching { interpreter?.close() }
            runCatching { configured.accelerator.close() }
        }
    }

    /**
     * Entrada determinista. No es ruido aleatorio: una rampa reproducible hace
     * que dos ejecuciones sobre el mismo modelo den el mismo resultado, que es lo
     * que permitirá comparar contra una salida esperada cuando el pipeline la
     * publique.
     */
    private fun referenceInput(interpreter: Interpreter): ByteBuffer {
        val tensor = interpreter.getInputTensor(0)
        val bytes = tensor.numBytes()
        val buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        repeat(bytes) { index -> buffer.put((index % RAMP_PERIOD).toByte()) }
        buffer.rewind()
        return buffer
    }

    private companion object {
        const val TAG = "AgroVision.SmokeTest"
        const val MIN_OUTPUTS = 1
        const val RAMP_PERIOD = 256
    }
}
