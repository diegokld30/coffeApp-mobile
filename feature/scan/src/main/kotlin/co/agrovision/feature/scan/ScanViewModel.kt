package co.agrovision.feature.scan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.MessageKey
import co.agrovision.core.common.UiText
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.port.PestClassifier
import co.agrovision.core.model.port.TelemetryRecorder
import co.agrovision.core.model.scan.ClassificationResult
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.usecase.ClassifyImageUseCase
import co.agrovision.core.model.usecase.ConfirmPredictionUseCase
import co.agrovision.core.model.usecase.CorrectPredictionUseCase
import co.agrovision.core.model.usecase.SaveCaptureUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la pantalla de escaneo.
 *
 * Expone **un solo** `onIntent`. La alternativa —un método público por acción—
 * hace que la pantalla pueda invocar cosas en órdenes que el estado no admite, y
 * el compilador no ayuda a impedirlo.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val classifyImage: ClassifyImageUseCase,
    private val saveCapture: SaveCaptureUseCase,
    private val confirmPrediction: ConfirmPredictionUseCase,
    private val correctPrediction: CorrectPredictionUseCase,
    private val classifier: PestClassifier,
    private val telemetry: TelemetryRecorder,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _effects = Channel<ScanEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onIntent(intent: ScanIntent) {
        when (intent) {
            ScanIntent.StartCamera -> warmUp()
            is ScanIntent.Capture -> analyze(intent.image)
            is ScanIntent.ConfirmPrediction -> confirm(intent.captureId)
            is ScanIntent.CorrectPrediction -> correct(intent.captureId, intent.classId)
            is ScanIntent.CreateReviewRequest -> emit(
                ScanEffect.NavigateToReviewRequest(intent.captureId)
            )
            ScanIntent.Retry -> backToPreview()
            ScanIntent.DismissResult -> backToPreview()
        }
    }

    private fun warmUp() {
        if (_state.value !is ScanUiState.Idle) return
        _state.value = ScanUiState.WarmingUp
        viewModelScope.launch {
            classifier.warmUp()
            // Sin modelo NO se bloquea la cámara: se entra igual, marcando que el
            // reconocedor no está. Documentar la plaga es la función que no puede
            // depender de la red.
            _state.value = ScanUiState.Preview(
                guidance = FramingGuidance.LISTO,
                modelReady = classifier.activeModelVersion() != null,
            )
        }
    }

    /**
     * Clasificar y **persistir antes de mostrar**.
     *
     * Ese orden es lo que sostiene el requisito de cero pérdidas ante cierre
     * forzado: cuando el productor ve el resultado, la fila ya existe en Room. Si
     * se mostrara primero, un cierre en ese instante perdería la captura y él
     * creería que quedó guardada.
     */
    private fun analyze(image: RgbImage) {
        _state.value = ScanUiState.Analyzing
        viewModelScope.launch {
            val classification = when (val result = classifyImage(image)) {
                is AgroResult.Failure -> when (result.error) {
                    // Sin modelo la app no sabe qué es, que es exactamente lo
                    // que significa «fuera de dominio». La captura se guarda y se
                    // ofrece revisión: el mismo camino que una plaga desconocida.
                    is DomainError.ModelUnavailable -> ClassificationResult.OutOfDomain(
                        oodScore = 0f,
                        modelVersion = NO_MODEL_VERSION,
                        latencyMillis = 0L,
                    )
                    else -> {
                        Log.w(TAG, "el análisis falló: ${result.error}")
                        _state.value = result.error.toErrorState()
                        return@launch
                    }
                }
                is AgroResult.Success -> result.value
            }

            // Cada escaneo cuenta para saber cómo se porta el modelo en campo, haya o no
            // consentimiento de entrenamiento: la métrica es anónima y no lleva la imagen.
            recordScan(classification)

            // Una imagen rechazada por calidad NO se persiste: no aporta nada al
            // dataset y ocuparía espacio en un teléfono que suele estar lleno.
            if (classification is ClassificationResult.QualityRejected) {
                Log.i(
                    TAG,
                    "compuerta 1 rechazó: ${classification.hint} " +
                        "medido=${classification.measured} umbral=${classification.threshold}",
                )
                _state.value = ScanUiState.QualityRejected(
                    hint = classification.hint,
                    measured = classification.measured,
                    threshold = classification.threshold,
                )
                return@launch
            }

            val saved = saveCapture(
                SaveCaptureUseCase.Request(
                    image = image,
                    result = classification,
                    embedding = null,
                    cropType = null,
                    plantOrgan = null,
                )
            )

            when (saved) {
                is AgroResult.Failure -> _state.value = saved.error.toErrorState()
                is AgroResult.Success -> _state.value = classification.toUiState(saved.value)
            }
        }
    }

    private suspend fun recordScan(result: ClassificationResult) {
        val (version, confidence, latency) = when (result) {
            is ClassificationResult.Identified -> Triple(result.modelVersion, result.confidence, result.latencyMillis)
            is ClassificationResult.NotAPest -> Triple(result.modelVersion, result.confidence, result.latencyMillis)
            is ClassificationResult.Uncertain -> Triple(
                result.modelVersion,
                result.candidates.firstOrNull()?.probability,
                result.latencyMillis,
            )
            is ClassificationResult.OutOfDomain -> Triple(result.modelVersion, null, result.latencyMillis)
            is ClassificationResult.QualityRejected -> Triple(classifier.activeModelVersion(), null, 0L)
        }
        // Sin modelo no hay versión que atribuir: el servidor exige una, así que no se manda
        if (version == null || version == NO_MODEL_VERSION) return

        telemetry.recordScan(
            modelVersion = version,
            outcome = result.outcome.name,
            confidence = confidence,
            latencyMillis = latency,
            delegate = "",
        )
    }

    private fun confirm(captureId: String) {
        viewModelScope.launch {
            confirmPrediction(captureId)
            emit(ScanEffect.ShowMessage(UiText.of(MessageKey.SCAN_PREDICTION_CONFIRMED)))
            backToPreview()
        }
    }

    private fun correct(captureId: String, classId: String) {
        viewModelScope.launch {
            correctPrediction(captureId, classId)
            emit(ScanEffect.ShowMessage(UiText.of(MessageKey.SCAN_PREDICTION_CORRECTED)))
            backToPreview()
        }
    }

    private fun backToPreview() {
        _state.value = ScanUiState.Preview(
            guidance = FramingGuidance.LISTO,
            modelReady = classifier.activeModelVersion() != null,
        )
    }

    private fun emit(effect: ScanEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }

    private fun ClassificationResult.toUiState(capture: Capture): ScanUiState = when (this) {
        is ClassificationResult.Identified -> ScanUiState.Identified(
            captureId = capture.id,
            pest = pest,
            confidence = confidence,
            top = top,
            // Con el modelo heredado no hay calibración, luego las compuertas 2 y
            // 3 no operan y la pantalla tiene que decirlo (ADR-014).
            uncertaintyGatesActive = oodScore != 0f,
            latencyMillis = latencyMillis,
        )

        is ClassificationResult.NotAPest -> ScanUiState.NotAPest(
            captureId = capture.id,
            label = label,
            confidence = confidence,
        )

        is ClassificationResult.Uncertain -> ScanUiState.Uncertain(
            captureId = capture.id,
            reason = reason,
            candidates = candidates,
        )

        is ClassificationResult.OutOfDomain -> ScanUiState.OutOfDomain(
            captureId = capture.id,
            modelWasAvailable = modelVersion != NO_MODEL_VERSION,
        )

        is ClassificationResult.QualityRejected ->
            ScanUiState.QualityRejected(hint, measured, threshold)
    }

    private fun DomainError.toErrorState() = ScanUiState.Error(
        message = UiText.Message(message),
        // Sin modelo no se recupera reintentando: hace falta conectarse. Los
        // demás fallos sí admiten reintento, y ofrecerlo cuando no sirve es peor
        // que no ofrecerlo.
        recoverable = this !is DomainError.ModelUnavailable,
    )

    /**
     * ⚠️ Liberación de la memoria nativa de LiteRT (§7.3).
     *
     * El recolector de basura de la JVM no la administra. Omitir esto produce
     * fugas que en dispositivos de 2 GB terminan en cierre forzado tras unas
     * cuantas navegaciones de entrada y salida de la cámara.
     */
    override fun onCleared() {
        classifier.release()
        super.onCleared()
    }

    private companion object {
        /** Marca las capturas tomadas sin reconocedor activo. Nunca deben
         *  contaminar las métricas de exactitud de una versión real. */
        const val TAG = "AgroVision.Scan"

        /** Marca las capturas tomadas sin reconocedor activo. Nunca deben
         *  contaminar las métricas de exactitud de una versión real. */
        const val NO_MODEL_VERSION = "sin-modelo"
    }
}
