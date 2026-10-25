package co.agrovision.feature.review.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.core.model.usecase.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SentOutcome {
    SENDING,

    /** Llegó al servidor con todas sus fotos. */
    DELIVERED,

    /** Guardada en el teléfono; sale sola cuando haya señal. */
    QUEUED,

    /** El token venció: sale en cuanto el productor vuelva a entrar. */
    SIGN_IN_REQUIRED,
}

/**
 * Después de enviar: intenta subir YA y muestra qué pasó.
 *
 * Lo que se muestra sale de Room, no del resultado de la llamada: si la subida
 * termina después de que el productor salga de la pantalla, la lista igual lo
 * refleja, y si vuelve aquí ve el estado real.
 */
@HiltViewModel
class ReviewSentViewModel @Inject constructor(
    savedState: SavedStateHandle,
    reviews: ReviewRequestRepository,
    account: AccountRepository,
    private val syncPending: SyncPendingUseCase,
) : ViewModel() {

    private val reviewId: String = checkNotNull(savedState["reviewId"])
    private val syncing = MutableStateFlow(true)

    val outcome: StateFlow<SentOutcome> = combine(
        reviews.observeById(reviewId),
        account.observe(),
        syncing,
    ) { review, current, inFlight ->
        when {
            review?.sync?.state == SyncState.UPLOADED -> SentOutcome.DELIVERED
            current.sessionExpired -> SentOutcome.SIGN_IN_REQUIRED
            inFlight -> SentOutcome.SENDING
            else -> SentOutcome.QUEUED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SentOutcome.SENDING)

    init {
        send()
    }

    fun send() {
        syncing.value = true
        viewModelScope.launch {
            // Sin señal, con la política de datos en contra o con la sesión
            // vencida, la solicitud queda en cola: el resultado ya se ve en Room
            // y en la cuenta, así que aquí no hace falta mirarlo.
            syncPending(SyncTrigger.MANUAL)
            syncing.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
