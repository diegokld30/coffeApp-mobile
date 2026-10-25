package co.agrovision.feature.scan

import co.agrovision.core.model.DomainError
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.scan.Embedding
import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.Prediction
import co.agrovision.core.model.scan.QualityAssessment
import co.agrovision.core.model.scan.QualityHint
import co.agrovision.core.model.scan.RawInference
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.scan.UncertaintyReason
import co.agrovision.core.model.usecase.ClassifyImageUseCase
import co.agrovision.core.model.usecase.ConfirmPredictionUseCase
import co.agrovision.core.model.usecase.CorrectPredictionUseCase
import co.agrovision.core.model.usecase.SaveCaptureUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pruebas del reductor MVI de la pantalla de escaneo.
 *
 * Se construyen los casos de uso **reales** sobre puertos falsos, en vez de
 * mockear los casos de uso. Eso hace que estas pruebas ejerciten de verdad la
 * cadena de tres compuertas y el orden de persistencia; con un mock del caso de
 * uso completo, invertir ese orden no rompería nada aquí.
 *
 * Dos comportamientos concentran el valor, y ninguno produce error visible al
 * romperse:
 *
 *  1. **Persistir antes de mostrar.** Si el resultado se muestra primero, un
 *     cierre en ese instante pierde la captura y el productor cree que quedó
 *     guardada.
 *  2. **No mostrar predicción cuando el modelo duda.** Es la primera de las tres
 *     apuestas del producto (§1.2) y lo más fácil de romper «para que la pantalla
 *     no se vea vacía».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    /**
     * `UnconfinedTestDispatcher`: la corrutina de `viewModelScope` corre con
     * avidez, así que la transición de estado ya ocurrió cuando la prueba mira.
     * Aquí no se prueba *cuándo* pasa algo sino *qué* estado resulta.
     */
    private val dispatcher = UnconfinedTestDispatcher()

    private val quality = FakeQualityInspector()
    private val gate = FakeUncertaintyGate()
    private val classifier = FakeClassifier()
    private val catalog = FakeCatalog(listOf(ROYA))
    private val captures = FakeCaptureRepository()
    private val images = FakeImageStore()
    private val settings = FakeSettings()
    private val telemetry = FakeTelemetry()

    private fun viewModel() = ScanViewModel(
        classifyImage = ClassifyImageUseCase(quality, classifier, gate, catalog),
        saveCapture = SaveCaptureUseCase(
            captures = captures,
            images = images,
            location = FakeLocationProvider(),
            settings = settings,
            clock = FixedClock(),
            ids = SequentialIds(),
        ),
        confirmPrediction = ConfirmPredictionUseCase(captures),
        correctPrediction = CorrectPredictionUseCase(captures, telemetry),
        classifier = classifier,
        telemetry = telemetry,
    )

    /**
     * `viewModelScope` NO es hijo del scope de la prueba, así que una excepción en
     * sus corrutinas no llega a `runTest`: se va al manejador del hilo y la prueba
     * falla con un estado inesperado en lugar de con la causa. Capturarlas
     * convierte un fallo mudo en uno que dice qué pasó.
     */
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        if (uncaught.isNotEmpty()) {
            throw AssertionError(
                "El ViewModel dejó escapar ${uncaught.size} excepción(es): " +
                    uncaught.joinToString("\n") { "${it::class.simpleName}: ${it.message}" },
                uncaught.first(),
            )
        }
    }

    // ── Precalentamiento ─────────────────────────────────────────────────────

    @Test
    fun `sin modelo activo la camara SIGUE funcionando`() = runTest(dispatcher) {
        classifier.version = null

        val subject = viewModel()
        subject.onIntent(ScanIntent.StartCamera)

        // Bloquear la cámara por falta de modelo convertiría un problema de red
        // en pérdida de trabajo de campo. El productor tiene que poder
        // documentar la plaga que está viendo (§5.2).
        val preview = assertInstanceOf(ScanUiState.Preview::class.java, subject.state.value)
        assertFalse(preview.modelReady)
    }

    @Test
    fun `sin modelo la captura se guarda y ofrece revision`() = runTest(dispatcher) {
        classifier.version = null

        val subject = readyViewModel()
        subject.onIntent(ScanIntent.Capture(image()))

        val state = assertInstanceOf(ScanUiState.OutOfDomain::class.java, subject.state.value)
        // La pantalla tiene que distinguir «miré y no lo reconozco» de «no tengo
        // con qué mirar». Decir lo mismo sería mentir sobre lo que la app hizo.
        assertFalse(state.modelWasAvailable)

        // Y sobre todo: la foto NO se pierde.
        assertEquals(1, captures.saved.size)
        assertEquals("sin-modelo", captures.saved.single().modelVersion)
    }

    @Test
    fun `una captura sin modelo no contamina las metricas de ninguna version real`() =
        runTest(dispatcher) {
            classifier.version = null

            val subject = readyViewModel()
            subject.onIntent(ScanIntent.Capture(image()))

            // Si estas filas llevaran una versión real, hundirían su tasa de
            // exactitud de campo sin que ese modelo hubiera hecho nada.
            assertEquals("sin-modelo", captures.saved.single().modelVersion)
        }

    @Test
    fun `el precalentamiento solo corre una vez`() = runTest(dispatcher) {
        val subject = viewModel()

        subject.onIntent(ScanIntent.StartCamera)
        subject.onIntent(ScanIntent.StartCamera)

        // Reconstruir el intérprete en cada recomposición es el camino corto a una
        // fuga de memoria nativa.
        assertEquals(1, classifier.warmUpCalls)
    }

    // ── El orden que sostiene «cero capturas perdidas» ───────────────────────

    @Test
    fun `la captura se persiste ANTES de mostrar el resultado`() = runTest(dispatcher) {
        givenIdentified()

        val subject = readyViewModel()
        subject.onIntent(ScanIntent.Capture(image()))

        val state = assertInstanceOf(ScanUiState.Identified::class.java, subject.state.value)
        // La fila ya existe cuando el resultado es visible, y el id que muestra la
        // pantalla es el de esa fila.
        assertEquals(1, captures.saved.size)
        assertEquals(captures.saved.single().id, state.captureId)
        assertEquals("Roya del café", state.pest.commonName)
    }

    @Test
    fun `si falla el guardado NO se muestra un resultado que no existe`() = runTest(dispatcher) {
        givenIdentified()
        images.result = co.agrovision.core.common.AgroResult.failure(
            DomainError.StorageFull
        )

        val subject = readyViewModel()
        subject.onIntent(ScanIntent.Capture(image()))

        // Mostrar el diagnóstico y perderlo al salir de la pantalla es peor que
        // decir que no se pudo guardar.
        assertInstanceOf(ScanUiState.Error::class.java, subject.state.value)
        assertTrue(captures.saved.isEmpty())
    }

    @Test
    fun `una imagen rechazada por calidad no se persiste ni llega al modelo`() =
        runTest(dispatcher) {
            quality.assessment = QualityAssessment.Reject(QualityHint.TOO_BLURRY, measured = 12f, threshold = 60f)

            val subject = readyViewModel()
            subject.onIntent(ScanIntent.Capture(image()))

            val state = assertInstanceOf(
                ScanUiState.QualityRejected::class.java,
                subject.state.value,
            )
            assertEquals(QualityHint.TOO_BLURRY, state.hint)
            // La compuerta 1 se evalúa ANTES de gastar cómputo en inferencia
            // (§7.2), y una imagen mala no ocupa espacio en un teléfono que suele
            // estar lleno.
            assertTrue(captures.saved.isEmpty())
        }

    // ── La primera apuesta del producto ──────────────────────────────────────

    @Test
    fun `cuando el modelo duda el estado NO contiene ninguna plaga identificada`() =
        runTest(dispatcher) {
            classifier.inference = inference()
            gate.decision = GateDecision.Uncertain(
                reason = UncertaintyReason.AMBIGUOUS,
                top = listOf(
                    Prediction("roya", "Roya", 0.48f, "pest-roya"),
                    Prediction("mancha", "Mancha de hierro", 0.45f, "pest-mancha"),
                ),
                calibratedConfidence = 0.48f,
                oodScore = 1.2f,
            )

            val subject = readyViewModel()
            subject.onIntent(ScanIntent.Capture(image()))

            // El tipo lo impide por construcción: `Uncertain` no tiene campo
            // `pest`. Esta prueba fija esa propiedad para que nadie la «arregle»
            // añadiéndolo para que la pantalla no se vea vacía.
            val state = assertInstanceOf(ScanUiState.Uncertain::class.java, subject.state.value)
            assertEquals(UncertaintyReason.AMBIGUOUS, state.reason)
            // La captura SÍ se guarda: es material valioso para el agrónomo.
            assertEquals(1, captures.saved.size)
        }

    @Test
    fun `una muestra fuera de dominio se guarda y ofrece revision`() = runTest(dispatcher) {
        classifier.inference = inference()
        gate.decision = GateDecision.OutOfDomain(
            oodScore = 9f,
            energyScore = 4f,
            mahalanobisDistance = 30f,
        )

        val subject = readyViewModel()
        subject.onIntent(ScanIntent.Capture(image()))

        assertInstanceOf(ScanUiState.OutOfDomain::class.java, subject.state.value)
        assertEquals(1, captures.saved.size)
    }

    // ── Retroalimentación del productor ──────────────────────────────────────

    @Test
    fun `corregir una prediccion registra telemetria con la version que fallo`() =
        runTest(dispatcher) {
            givenIdentified()
            val subject = readyViewModel()
            subject.onIntent(ScanIntent.Capture(image()))
            val captureId = captures.saved.single().id

            subject.onIntent(ScanIntent.CorrectPrediction(captureId, "mancha"))

            assertEquals(captureId to "mancha", captures.corrected.single())
            // Sin la versión de modelo no se puede atribuir la corrección, y la
            // tasa de corrección es una de las señales que disparan la reversión
            // (§8.4).
            assertEquals("2.0.0", telemetry.corrections.single().first)
        }

    // ── Ciclo de vida nativo ─────────────────────────────────────────────────

    @Test
    fun `al destruir el ViewModel se libera la memoria nativa del interprete`() {
        val subject = viewModel()

        subject.invokeOnCleared()

        // El recolector de basura de la JVM no administra la memoria de LiteRT.
        // Sin esta llamada, entrar y salir de la cámara unas cuantas veces agota
        // la memoria en un dispositivo de 2 GB.
        assertTrue(classifier.released)
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    /** ViewModel ya precalentado, en estado `Preview`. */
    private fun readyViewModel() = viewModel().also { it.onIntent(ScanIntent.StartCamera) }

    private fun givenIdentified() {
        classifier.inference = inference()
        gate.decision = GateDecision.InDomain(
            top = listOf(Prediction("roya", "Roya", 0.91f, "pest-roya")),
            calibratedConfidence = 0.91f,
            oodScore = 0.4f,
        )
    }

    private fun image() = RgbImage(2, 2, IntArray(4))

    private fun inference() = RawInference(
        logits = floatArrayOf(4f, 1f, 0.5f),
        embedding = Embedding(floatArrayOf(1f, 2f, 3f)),
        modelVersion = "2.0.0",
        latencyMillis = 280L,
    )

    private companion object {
        val ROYA = PestTaxon(
            id = "pest-roya",
            scientificName = "Hemileia vastatrix",
            commonName = "Roya del café",
            localSynonyms = listOf("roya"),
            crop = CropType.CAFE,
            affectedOrgans = emptyList(),
            description = "",
            recommendations = emptyList(),
            imageRefs = emptyList(),
            version = 1,
            kind = co.agrovision.core.model.pest.PestKind.ENFERMEDAD,
            reference = null,
            approval = null,
        )
    }
}

/**
 * `onCleared` es `protected` en `ViewModel`. Se invoca por reflexión porque el
 * comportamiento que hay que verificar —liberar memoria nativa— es exactamente el
 * que se pierde si nadie lo prueba.
 */
private fun androidx.lifecycle.ViewModel.invokeOnCleared() {
    val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}
