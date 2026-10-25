package co.agrovision.ml.ood

import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.ModelLabel
import co.agrovision.core.model.port.UncertaintyGate
import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.Prediction
import co.agrovision.core.model.scan.RawInference
import co.agrovision.core.model.scan.UncertaintyReason
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * **Compuertas 2 y 3** — detección fuera de distribución y calibración (§7.2).
 *
 * ## Por qué esto no es un umbral de softmax
 *
 * La intuición habitual —«si la confianza es menor al 60 %, es desconocida»— es
 * incorrecta y peligrosa, y es el error prohibido nº 4 (§23).
 *
 * Las redes neuronales modernas están sistemáticamente sobreconfiadas. Una plaga
 * que el modelo nunca vio durante el entrenamiento puede producir un softmax de
 * 0,95 sobre la clase visualmente más parecida. El umbral de confianza detecta la
 * **duda** del modelo; no detecta su **ignorancia**. Y lo que hay que detectar es
 * precisamente la ignorancia.
 *
 * Por eso la compuerta 2 opera sobre el **embedding**, no sobre el softmax, y
 * combina dos señales que fallan de formas distintas:
 *
 * **Energía** — `E(x) = -T · log Σ exp(z_i / T)`. Es una estimación no normalizada
 * de la densidad de la muestra bajo el modelo. A diferencia del softmax, no está
 * normalizada a sumar 1, así que conserva la magnitud absoluta de los logits: una
 * muestra que activa poco toda la red tiene energía alta aunque su softmax sea
 * puntiagudo.
 *
 * **Mahalanobis** — distancia del embedding al centroide de la clase predicha,
 * ponderada por la inversa de la covarianza compartida. Mide «cuán lejos está
 * esto de lo que el modelo llama esa clase», en unidades de la dispersión propia
 * de los datos de entrenamiento.
 *
 * Se combinan porque se equivocan en casos distintos: la energía falla con
 * muestras que activan mucho la red por razones espurias, y Mahalanobis falla
 * cuando el embedding cae dentro del cono de una clase pero por textura, no por
 * contenido.
 *
 * ## Compuerta 3
 *
 * Escalado por temperatura sobre los logits, con la T óptima calculada en
 * validación y embarcada en los metadatos del modelo. Después, dos criterios:
 *
 * - `p_max < τ` — el modelo no se compromete.
 * - `margen(p1, p2) < δ` — **detecta la confusión entre clases visualmente
 *   parecidas, que el primer criterio deja pasar.** Un modelo que reparte 0,48 y
 *   0,45 entre roya y mancha de hierro no está seguro, aunque 0,48 supere
 *   cualquier τ razonable.
 */
class EnergyMahalanobisGate(
    private val calibration: CalibrationParams,
    private val labels: List<ModelLabel>,
) : UncertaintyGate {

    init {
        require(calibration.temperature > 0f) {
            "La temperatura debe ser positiva; llegó ${calibration.temperature}"
        }
        require(labels.isNotEmpty()) { "Un modelo sin etiquetas no puede evaluarse" }
    }

    override fun evaluate(inference: RawInference): GateDecision {
        val logits = inference.logits
        require(logits.size == labels.size) {
            "Los logits (${logits.size}) no cuadran con las etiquetas (${labels.size}). " +
                "Es un modelo y un labels.json de versiones distintas."
        }

        val probabilities = softmaxWithTemperature(logits, calibration.temperature)
        val ranked = probabilities.rankTop(TOP_K)
        val topIndex = ranked.first().first
        val topProbability = ranked.first().second

        // ── Compuerta 2 ──
        // Un modelo simple no tiene embedding: no hay forma de saber si la muestra
        // es desconocida, así que la compuerta no existe y todo lo decide la 3.
        val energy = if (calibration.isSimple) 0f else energyScore(logits, calibration.temperature)
        val mahalanobis = if (calibration.isSimple) 0f else mahalanobisDistance(inference.embedding.values, topIndex)
        val combined = if (calibration.isSimple) 0f else combine(energy, mahalanobis)

        if (!calibration.isSimple && combined > calibration.oodThreshold) {
            return GateDecision.OutOfDomain(
                oodScore = combined,
                energyScore = energy,
                mahalanobisDistance = mahalanobis,
            )
        }

        // ── Compuerta 3 ──
        val predictions = ranked.map { (index, probability) ->
            val label = labels[index]
            Prediction(
                classId = label.classId,
                label = label.displayName,
                probability = probability,
                pestId = label.pestId,
            )
        }

        val runnerUp = ranked.getOrNull(1)?.second ?: 0f
        val margin = topProbability - runnerUp

        val reason = when {
            topProbability < calibration.confidenceThreshold -> UncertaintyReason.LOW_CONFIDENCE
            margin < calibration.marginThreshold -> UncertaintyReason.AMBIGUOUS
            else -> null
        }

        return if (reason == null) {
            GateDecision.InDomain(
                top = predictions,
                calibratedConfidence = topProbability,
                oodScore = combined,
            )
        } else {
            GateDecision.Uncertain(
                reason = reason,
                top = predictions,
                calibratedConfidence = topProbability,
                oodScore = combined,
            )
        }
    }

    // ── Matemática ───────────────────────────────────────────────────────────

    /**
     * Softmax con escalado por temperatura, estabilizado restando el máximo.
     *
     * Sin la resta, `exp(logit)` desborda a infinito con logits por encima de ~88
     * en precisión simple, y el resultado es `NaN` repartido por toda la
     * distribución. Con INT8 dequantizado los logits pueden llegar altos, así que
     * no es un caso teórico.
     */
    internal fun softmaxWithTemperature(logits: FloatArray, temperature: Float): FloatArray {
        val scaled = FloatArray(logits.size) { logits[it] / temperature }
        val max = scaled.max()
        var sum = 0.0
        val exponentials = DoubleArray(scaled.size) { index ->
            exp((scaled[index] - max).toDouble()).also { sum += it }
        }
        return FloatArray(scaled.size) { (exponentials[it] / sum).toFloat() }
    }

    /**
     * `E(x) = -T · log Σ exp(z_i / T)`.
     *
     * Energía **alta** = muestra menos probable bajo el modelo. Se estabiliza con
     * el mismo truco del máximo, reincorporándolo después: `log Σ exp(z_i) =
     * max + log Σ exp(z_i - max)`.
     *
     * Se normaliza con la media y desviación calculadas en validación para que
     * sea combinable con Mahalanobis, que vive en otra escala por completo.
     */
    internal fun energyScore(logits: FloatArray, temperature: Float): Float {
        val scaled = FloatArray(logits.size) { logits[it] / temperature }
        val max = scaled.max()
        var sum = 0.0
        scaled.forEach { sum += exp((it - max).toDouble()) }
        val logSumExp = max + ln(sum).toFloat()
        val energy = -temperature * logSumExp

        val stdDev = calibration.energyStdDev.takeIf { it > EPSILON } ?: 1f
        return (energy - calibration.energyMean) / stdDev
    }

    /**
     * Distancia de Mahalanobis al centroide de la clase predicha:
     *
     * ```
     *   d(x, μ) = sqrt( (x - μ)ᵀ · Σ⁻¹ · (x - μ) )
     * ```
     *
     * `Σ⁻¹` es la precisión compartida entre clases, calculada en entrenamiento y
     * embarcada aplanada. Compartirla en vez de tener una por clase es lo
     * habitual: con 300–800 muestras por clase y un embedding de 256 dimensiones,
     * una covarianza por clase estaría mal condicionada.
     *
     * El producto es O(d²) — 65 536 multiplicaciones con d=256. En un Snapdragon
     * 680 son unos pocos milisegundos, despreciable frente a la inferencia. Si
     * alguna vez dejara de serlo, la salida es aproximar Σ⁻¹ por su diagonal.
     */
    internal fun mahalanobisDistance(embedding: FloatArray, classIndex: Int): Float {
        val centroid = calibration.classCentroids.getOrNull(classIndex) ?: return 0f
        if (centroid.size != embedding.size) return 0f

        val dimension = embedding.size
        if (calibration.precisionMatrix.size != dimension * dimension) return 0f

        val delta = FloatArray(dimension) { embedding[it] - centroid[it] }

        var quadratic = 0.0
        for (row in 0 until dimension) {
            var accumulated = 0.0
            val offset = row * dimension
            for (column in 0 until dimension) {
                accumulated += calibration.precisionMatrix[offset + column] * delta[column]
            }
            quadratic += delta[row] * accumulated
        }

        // La forma cuadrática es teóricamente no negativa, pero Σ⁻¹ llega
        // cuantizada desde el pipeline de entrenamiento y puede perder la
        // definición positiva por redondeo. Un valor negativo aquí produciría
        // `NaN` en la raíz, y un `NaN` comparado con el umbral devuelve `false`:
        // la muestra pasaría la compuerta OOD en silencio. Se acota.
        return sqrt(quadratic.coerceAtLeast(0.0)).toFloat()
    }

    /**
     * Combinación lineal ponderada. `energyWeight` viene calibrado sobre un
     * conjunto de validación que incluye deliberadamente muestras fuera de
     * dominio; no es una constante inventada aquí.
     */
    private fun combine(energy: Float, mahalanobis: Float): Float {
        val weight = calibration.energyWeight.coerceIn(0f, 1f)
        return weight * energy + (1f - weight) * mahalanobis
    }

    private fun FloatArray.rankTop(k: Int): List<Pair<Int, Float>> =
        mapIndexed { index, value -> index to value }
            .sortedByDescending { it.second }
            .take(k.coerceAtMost(size))

    private companion object {
        const val TOP_K = 3
        const val EPSILON = 1e-6f
    }
}
