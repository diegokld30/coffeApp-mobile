package co.agrovision.feature.review.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.UiText
import co.agrovision.core.model.identity.CoffeeVariety
import co.agrovision.core.model.identity.PlantingAge
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.IdGenerator
import co.agrovision.core.model.port.ImageStore
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.usecase.CreateReviewRequestUseCase
import co.agrovision.feature.review.components.GUIDED_ANGLES
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Una foto ya escrita en disco (sin EXIF, con miniatura). */
data class ReviewPhoto(val id: String, val imagePath: String, val thumbnailPath: String)

sealed interface CreateStage {
    /** Cámara abierta pidiendo esta toma. */
    data class Capturing(val angle: CaptureAngle) : CreateStage

    /** Escribiendo la foto a disco. Unos cientos de milisegundos. */
    data class Saving(val angle: CaptureAngle) : CreateStage

    /** La foto recién tomada, para aceptarla o repetirla. */
    data class Confirming(val angle: CaptureAngle, val photo: ReviewPhoto) : CreateStage

    data object Form : CreateStage
}

data class CreateReviewState(
    val stage: CreateStage = CreateStage.Capturing(GUIDED_ANGLES.first()),
    /** La foto del escaneo que no se reconoció. Abre la solicitud. */
    val original: ReviewPhoto? = null,
    val shots: Map<CaptureAngle, ReviewPhoto> = emptyMap(),
    val cropType: CropType = CropType.CAFE,
    val plantOrgan: PlantOrgan = PlantOrgan.HOJA,
    val note: String = "",
    val variety: CoffeeVariety? = null,
    val plantingAge: PlantingAge? = null,
    val submitting: Boolean = false,
    val error: UiText? = null,
) {
    val photoCount: Int get() = shots.size + if (original != null) 1 else 0

    /** Índice de la toma en curso, para la barra de pasos. */
    fun stepOf(angle: CaptureAngle): Int = GUIDED_ANGLES.indexOf(angle)
}

sealed interface CreateReviewIntent {
    data class Captured(val image: RgbImage) : CreateReviewIntent
    data object AcceptShot : CreateReviewIntent
    data object RetakeShot : CreateReviewIntent
    data class RetakeAngle(val angle: CaptureAngle) : CreateReviewIntent
    data class SetCrop(val crop: CropType) : CreateReviewIntent
    data class SetOrgan(val organ: PlantOrgan) : CreateReviewIntent
    data class SetNote(val note: String) : CreateReviewIntent
    data object Submit : CreateReviewIntent
    data object Cancel : CreateReviewIntent
}

sealed interface CreateReviewEffect {
    /** Guardada. Falta entrar con OTP o completar el perfil antes de enviarla. */
    data class SignIn(val reviewId: String) : CreateReviewEffect
    data class Sent(val reviewId: String) : CreateReviewEffect
    data object Closed : CreateReviewEffect
}

/**
 * Solicitud de Revisión a partir de un escaneo que no se reconoció.
 *
 * La foto del escaneo abre la solicitud y se piden **cuatro más, guiadas**: la
 * planta entera, la hoja por arriba, la hoja por abajo y el daño de cerca. Cinco
 * en total, el máximo que acepta una solicitud. Un agrónomo no dictamina sobre
 * una sola toma de una lesión foliar.
 *
 * Cada toma se escribe a disco en cuanto se dispara (no se retiene el bitmap:
 * cinco fotos en memoria en un teléfono de 2 GB es buscarse un cierre), y se
 * borra si se repite o si se cancela la solicitud.
 *
 * La solicitud se **guarda antes** de pedir cuenta: si el productor abandona el
 * registro a medias, no pierde las fotos (§6.3).
 */
@HiltViewModel
class CreateReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val captures: CaptureRepository,
    private val images: ImageStore,
    private val ids: IdGenerator,
    private val account: AccountRepository,
    private val createReviewRequest: CreateReviewRequestUseCase,
) : ViewModel() {

    private val captureId: String = checkNotNull(savedState["captureId"])

    private val _state = MutableStateFlow(CreateReviewState())
    val state: StateFlow<CreateReviewState> = _state.asStateFlow()

    private val _effects = Channel<CreateReviewEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Al repetir una toma desde el formulario, se vuelve al formulario y no a la
     *  siguiente toma. */
    private var retakingFromForm = false

    init {
        viewModelScope.launch {
            val capture = captures.findById(captureId)
            val current = account.current()
            _state.update { state ->
                state.copy(
                    original = capture?.let {
                        ReviewPhoto(id = ids.newId(), imagePath = it.imagePath, thumbnailPath = it.thumbnailPath)
                    },
                    cropType = capture?.cropType ?: state.cropType,
                    plantOrgan = capture?.plantOrgan ?: state.plantOrgan,
                    variety = current.coffeeVariety,
                    plantingAge = current.plantingAge,
                )
            }
        }
    }

    fun onIntent(intent: CreateReviewIntent) {
        when (intent) {
            is CreateReviewIntent.Captured -> persistShot(intent.image)
            CreateReviewIntent.AcceptShot -> acceptShot()
            CreateReviewIntent.RetakeShot -> retakeShot()
            is CreateReviewIntent.RetakeAngle -> {
                retakingFromForm = true
                _state.update { it.copy(stage = CreateStage.Capturing(intent.angle)) }
            }
            is CreateReviewIntent.SetCrop -> _state.update { it.copy(cropType = intent.crop) }
            is CreateReviewIntent.SetOrgan -> _state.update { it.copy(plantOrgan = intent.organ) }
            is CreateReviewIntent.SetNote -> _state.update { it.copy(note = intent.note) }
            CreateReviewIntent.Submit -> submit()
            CreateReviewIntent.Cancel -> cancel()
        }
    }

    private fun persistShot(image: RgbImage) {
        val angle = (_state.value.stage as? CreateStage.Capturing)?.angle ?: return
        _state.update { it.copy(stage = CreateStage.Saving(angle)) }
        viewModelScope.launch {
            val id = ids.newId()
            when (val stored = images.persist(id, image)) {
                is AgroResult.Success -> _state.update {
                    it.copy(
                        stage = CreateStage.Confirming(
                            angle = angle,
                            photo = ReviewPhoto(id, stored.value.imagePath, stored.value.thumbnailPath),
                        ),
                        error = null,
                    )
                }
                is AgroResult.Failure -> _state.update {
                    it.copy(stage = CreateStage.Capturing(angle), error = UiText.Message(stored.error.message))
                }
            }
        }
    }

    private fun acceptShot() {
        val confirming = _state.value.stage as? CreateStage.Confirming ?: return
        val replaced = _state.value.shots[confirming.angle]
        val shots = _state.value.shots + (confirming.angle to confirming.photo)
        val next = GUIDED_ANGLES.firstOrNull { it !in shots }?.takeUnless { retakingFromForm }
        retakingFromForm = false

        _state.update {
            it.copy(
                shots = shots,
                stage = next?.let { angle -> CreateStage.Capturing(angle) } ?: CreateStage.Form,
            )
        }
        replaced?.let { deleteFiles(listOf(it)) }
    }

    private fun retakeShot() {
        val confirming = _state.value.stage as? CreateStage.Confirming ?: return
        deleteFiles(listOf(confirming.photo))
        _state.update { it.copy(stage = CreateStage.Capturing(confirming.angle)) }
    }

    private fun submit() {
        val state = _state.value
        if (state.submitting) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            // La foto del escaneo es la que no se reconoció: va primero y como
            // detalle, que es como el productor la encuadró.
            val photos = listOfNotNull(state.original?.let { it to CaptureAngle.DETALLE }) +
                GUIDED_ANGLES.mapNotNull { angle -> state.shots[angle]?.let { it to angle } }

            val result = createReviewRequest(
                CreateReviewRequestUseCase.Request(
                    captureId = captureId,
                    images = photos.map { (photo, angle) ->
                        ReviewImage(
                            id = photo.id,
                            imagePath = photo.imagePath,
                            thumbnailPath = photo.thumbnailPath,
                            angle = angle,
                            embedding = null,
                        )
                    },
                    cropType = state.cropType,
                    plantOrgan = state.plantOrgan,
                    noteText = state.note.trim().takeIf { it.isNotEmpty() },
                    noteAudioPath = null,
                )
            )

            when (result) {
                is AgroResult.Failure -> _state.update {
                    it.copy(submitting = false, error = UiText.Message(result.error.message))
                }
                is AgroResult.Success -> {
                    val current = account.current()
                    val effect = if (current.needsSignIn || current.needsProfile) {
                        CreateReviewEffect.SignIn(result.value.id)
                    } else {
                        CreateReviewEffect.Sent(result.value.id)
                    }
                    _effects.send(effect)
                }
            }
        }
    }

    /** Cancelar borra las tomas guiadas. La del escaneo NO: pertenece a la
     *  captura y sigue en el historial. */
    private fun cancel() {
        val state = _state.value
        val pending = state.shots.values.toList() +
            listOfNotNull((state.stage as? CreateStage.Confirming)?.photo)
        deleteFiles(pending)
        viewModelScope.launch { _effects.send(CreateReviewEffect.Closed) }
    }

    private fun deleteFiles(photos: List<ReviewPhoto>) {
        if (photos.isEmpty()) return
        viewModelScope.launch {
            images.delete(photos.flatMap { listOf(it.imagePath, it.thumbnailPath) })
        }
    }
}
