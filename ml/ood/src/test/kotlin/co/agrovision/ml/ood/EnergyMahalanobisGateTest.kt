package co.agrovision.ml.ood

import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.ModelLabel
import co.agrovision.core.model.scan.Embedding
import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.RawInference
import co.agrovision.core.model.scan.UncertaintyReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Pruebas de las compuertas 2 y 3.
 *
 * La prueba que importa de verdad es
 * [una muestra fuera de dominio se detecta aunque el softmax sea altisimo].
 * Es el caso que hunde a la mayoría de implementaciones: si esa pasa, el sistema
 * detecta ignorancia y no solo duda. Si esa falla, todo lo demás es decorado.
 */
class EnergyMahalanobisGateTest {

    // Espacio de embedding de 3 dimensiones y 3 clases: pequeño para poder
    // razonar la aritmética a mano, que es lo que hace que estas pruebas se
    // puedan auditar en lugar de creer.
    private val labels = listOf(
        ModelLabel(0, "Enfermedad_Roya", "Roya del café", "pest-roya"),
        ModelLabel(1, "Enfermedad_ManchadeHierro", "Mancha de hierro", "pest-mancha"),
        ModelLabel(2, "Rechazo", "No es una plaga", null),
    )

    /** Identidad como precisión: Mahalanobis se reduce a distancia euclídea, que
     *  se puede verificar mentalmente. */
    private val identity3 = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    private fun calibration(
        temperature: Float = 1.5f,
        confidenceThreshold: Float = 0.70f,
        marginThreshold: Float = 0.15f,
        oodThreshold: Float = 3.0f,
        energyWeight: Float = 0.5f,
    ) = CalibrationParams(
        temperature = temperature,
        confidenceThreshold = confidenceThreshold,
        marginThreshold = marginThreshold,
        oodThreshold = oodThreshold,
        energyWeight = energyWeight,
        classCentroids = listOf(
            floatArrayOf(10f, 0f, 0f),
            floatArrayOf(0f, 10f, 0f),
            floatArrayOf(0f, 0f, 10f),
        ),
        precisionMatrix = identity3,
        energyMean = -5f,
        energyStdDev = 1f,
    )

    private fun inference(logits: FloatArray, embedding: FloatArray) = RawInference(
        logits = logits,
        embedding = Embedding(embedding),
        modelVersion = "prueba-1.0.0",
        latencyMillis = 120L,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // LA prueba
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una muestra fuera de dominio se detecta aunque el softmax sea altisimo")
    fun `una muestra fuera de dominio se detecta aunque el softmax sea altisimo`() {
        // Este es exactamente el escenario que describe §7.2: una plaga que el
        // modelo nunca vio produce un softmax puntiagudo sobre la clase
        // visualmente parecida. Cualquier detector basado en umbral de confianza
        // la deja pasar como «roya, 99 %» y el productor fumiga sin necesidad.
        val logits = floatArrayOf(20f, 1f, 1f)
        val embedding = floatArrayOf(100f, 100f, 100f) // lejísimos de todo centroide

        val gate = EnergyMahalanobisGate(calibration(), labels)
        val probabilities = gate.softmaxWithTemperature(logits, 1.5f)

        // Primero: confirmamos que el softmax REALMENTE es altísimo, para que la
        // prueba no pase por accidente sobre una entrada poco convincente.
        assertTrue(
            probabilities[0] > 0.99f,
            "la premisa exige un softmax sobreconfiado; fue ${probabilities[0]}",
        )

        val decision = gate.evaluate(inference(logits, embedding))

        assertInstanceOf(
            GateDecision.OutOfDomain::class.java,
            decision,
            "un softmax de ${probabilities[0]} NO puede bastar para dar por buena una " +
                "muestra cuyo embedding está fuera de la distribución de entrenamiento",
        )
    }

    @Test
    fun `una muestra dentro de dominio con logits comparables si se identifica`() {
        val logits = floatArrayOf(8f, 1f, 1f)
        val embedding = floatArrayOf(10f, 0f, 0f) // encima del centroide de la clase 0

        val decision = EnergyMahalanobisGate(calibration(), labels)
            .evaluate(inference(logits, embedding))

        val identified = assertInstanceOf(GateDecision.InDomain::class.java, decision)
        assertEquals("Enfermedad_Roya", identified.top.first().classId)
        assertEquals("pest-roya", identified.top.first().pestId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compuerta 3
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `probabilidad maxima por debajo de tau produce incertidumbre por confianza`() {
        // Logits planos: reparte casi por igual y ninguna clase alcanza τ=0,70.
        val logits = floatArrayOf(1.0f, 0.9f, 0.8f)
        val embedding = floatArrayOf(10f, 0f, 0f)

        val decision = EnergyMahalanobisGate(calibration(), labels)
            .evaluate(inference(logits, embedding))

        val uncertain = assertInstanceOf(GateDecision.Uncertain::class.java, decision)
        assertEquals(UncertaintyReason.LOW_CONFIDENCE, uncertain.reason)
    }

    @Test
    fun `margen insuficiente entre primera y segunda clase produce ambiguedad`() {
        // Este es el caso que el criterio de probabilidad máxima DEJA PASAR y que
        // por eso existe el segundo criterio: dos clases visualmente parecidas
        // repartiéndose la masa. τ bajo para aislar el efecto del margen.
        val logits = floatArrayOf(6.0f, 5.85f, 0.1f)
        val embedding = floatArrayOf(10f, 0f, 0f)

        val gate = EnergyMahalanobisGate(
            calibration(confidenceThreshold = 0.30f, marginThreshold = 0.15f),
            labels,
        )
        val probabilities = gate.softmaxWithTemperature(logits, 1.5f)

        // Premisa: la primera clase SÍ supera τ. Si no, estaríamos probando el
        // otro criterio sin darnos cuenta.
        assertTrue(
            probabilities[0] > 0.30f,
            "la premisa exige superar τ; fue ${probabilities[0]}",
        )
        assertTrue(
            probabilities[0] - probabilities[1] < 0.15f,
            "la premisa exige margen estrecho; fue ${probabilities[0] - probabilities[1]}",
        )

        val decision = gate.evaluate(inference(logits, embedding))

        val uncertain = assertInstanceOf(GateDecision.Uncertain::class.java, decision)
        assertEquals(UncertaintyReason.AMBIGUOUS, uncertain.reason)
    }

    @Test
    fun `la clase de rechazo se propaga sin pestId`() {
        val logits = floatArrayOf(0.1f, 0.1f, 9f)
        val embedding = floatArrayOf(0f, 0f, 10f)

        val decision = EnergyMahalanobisGate(calibration(), labels)
            .evaluate(inference(logits, embedding))

        val identified = assertInstanceOf(GateDecision.InDomain::class.java, decision)
        val top = identified.top.first()
        assertEquals("Rechazo", top.classId)
        assertTrue(top.isRejectClass, "la clase de rechazo no puede llevar pestId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Estabilidad numérica — modos de fallo reales con INT8 dequantizado
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `logits enormes no desbordan el softmax a NaN`() {
        // Sin estabilizar restando el máximo, exp(700) desborda y toda la
        // distribución sale NaN. Un NaN comparado con el umbral devuelve false,
        // así que la muestra pasaría la compuerta OOD en silencio.
        val logits = floatArrayOf(700f, 690f, 1f)
        val gate = EnergyMahalanobisGate(calibration(), labels)

        val probabilities = gate.softmaxWithTemperature(logits, 1.5f)

        probabilities.forEach { assertFalse(it.isNaN(), "el softmax produjo NaN") }
        assertTrue(abs(probabilities.sum() - 1f) < 1e-4f, "el softmax debe sumar 1")
    }

    @Test
    fun `una matriz de precision mal condicionada no produce NaN en la distancia`() {
        // Σ⁻¹ llega cuantizada desde el pipeline de entrenamiento y puede perder
        // la definición positiva por redondeo. La forma cuadrática saldría
        // negativa y la raíz cuadrada, NaN.
        val degenerate = calibration().copy(
            precisionMatrix = floatArrayOf(
                -1f, 0f, 0f,
                0f, -1f, 0f,
                0f, 0f, -1f,
            )
        )
        val gate = EnergyMahalanobisGate(degenerate, labels)

        val distance = gate.mahalanobisDistance(floatArrayOf(5f, 5f, 5f), 0)

        assertFalse(distance.isNaN(), "la distancia nunca puede ser NaN")
        assertTrue(distance >= 0f)
    }

    @Test
    fun `un embedding de dimension distinta a la calibracion no revienta`() {
        // Ocurre cuando el .tflite y el calibration.json son de versiones
        // distintas. Debe degradar, no lanzar.
        val gate = EnergyMahalanobisGate(calibration(), labels)

        val distance = gate.mahalanobisDistance(floatArrayOf(1f, 2f), 0)

        assertEquals(0f, distance)
    }

    @Test
    fun `logits que no cuadran con las etiquetas fallan de inmediato y con mensaje claro`() {
        val gate = EnergyMahalanobisGate(calibration(), labels)

        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            gate.evaluate(inference(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f)))
        }

        // Fallar rápido y visible: un modelo con labels.json de otra versión
        // produciría diagnósticos con el nombre de plaga equivocado.
        assertTrue(error.message!!.contains("versiones distintas"))
    }

    @Test
    fun `la temperatura suaviza la sobreconfianza`() {
        val logits = floatArrayOf(8f, 2f, 1f)
        val gate = EnergyMahalanobisGate(calibration(), labels)

        val sinEscalar = gate.softmaxWithTemperature(logits, 1.0f)
        val escalado = gate.softmaxWithTemperature(logits, 3.0f)

        // Esa es toda la función del escalado por temperatura: reducir la
        // confianza sistemáticamente inflada, sin cambiar el orden.
        assertTrue(
            escalado[0] < sinEscalar[0],
            "T>1 debe bajar la probabilidad máxima: ${escalado[0]} vs ${sinEscalar[0]}",
        )
        assertEquals(0, escalado.indices.maxByOrNull { escalado[it] })
    }

    @Test
    fun `una temperatura no positiva se rechaza al construir, no al inferir`() {
        // Fallar al construir significa fallar al ACTIVAR el modelo, cuando aún
        // se puede revertir a la ranura anterior. Fallar al inferir significa
        // fallar delante del productor, en el lote.
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            EnergyMahalanobisGate(calibration(temperature = 0f), labels)
        }
    }

    @Test
    @DisplayName("un modelo simple, sin embedding, decide solo por confianza y margen")
    fun simpleModelUsesOnlyTheConfidenceGate() {
        val simple = CalibrationParams(
            temperature = 1f,
            confidenceThreshold = 0.55f,
            marginThreshold = 0.10f,
            oodThreshold = 1e9f,
            energyWeight = 0f,
            classCentroids = emptyList(),
            precisionMatrix = floatArrayOf(),
            energyMean = 0f,
            energyStdDev = 1f,
        )
        val gate = EnergyMahalanobisGate(simple, labels)

        // La salida del modelo son probabilidades; la app las pasa a logits con ln
        fun logitsOf(vararg probabilities: Float) = FloatArray(probabilities.size) { kotlin.math.ln(probabilities[it]) }

        val sure = gate.evaluate(inference(logitsOf(0.90f, 0.07f, 0.03f), floatArrayOf()))
        assertInstanceOf(GateDecision.InDomain::class.java, sure)
        assertTrue(abs((sure as GateDecision.InDomain).calibratedConfidence - 0.90f) < 1e-4f)

        val unsure = gate.evaluate(inference(logitsOf(0.40f, 0.35f, 0.25f), floatArrayOf()))
        assertInstanceOf(GateDecision.Uncertain::class.java, unsure)
    }
}
