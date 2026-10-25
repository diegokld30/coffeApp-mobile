package co.agrovision.feature.review.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.core.model.usecase.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewListState(
    val loading: Boolean = true,
    val requests: List<ReviewRequest> = emptyList(),
    val unreadIds: Set<String> = emptySet(),
    val expandedId: String? = null,
    val sessionExpired: Boolean = false,
    val sending: Boolean = false,
) {
    /** En cola local: por subir o esperando reintento. Las FAILED no cuentan:
     *  «Enviar ahora» no las mueve. */
    val pendingCount: Int get() = requests.count { it.sync.state == SyncState.PENDING }
}

/**
 * Mis solicitudes. Todo sale de Room: funciona sin señal y muestra la respuesta
 * del agrónomo aunque haya llegado estando en el pueblo.
 */
@HiltViewModel
class ReviewListViewModel @Inject constructor(
    private val reviews: ReviewRequestRepository,
    account: AccountRepository,
    private val syncPending: SyncPendingUseCase,
) : ViewModel() {

    private val expanded = MutableStateFlow<String?>(null)
    private val sending = MutableStateFlow(false)

    val state: StateFlow<ReviewListState> = combine(
        reviews.observeMine(),
        reviews.observeUnreadResolutions().map { unread -> unread.map { it.id }.toSet() },
        account.observe(),
        expanded,
        sending,
    ) { requests, unread, current, expandedId, isSending ->
        ReviewListState(
            loading = false,
            requests = requests,
            unreadIds = unread,
            expandedId = expandedId,
            sessionExpired = current.sessionExpired,
            sending = isSending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReviewListState())

    /** Abrir una respondida la marca como leída: es lo que apaga el distintivo. */
    fun onToggle(request: ReviewRequest) {
        expanded.value = if (expanded.value == request.id) null else request.id
        if (request.id in state.value.unreadIds) {
            viewModelScope.launch { reviews.markResolutionRead(request.id) }
        }
    }

    /** También trae las respuestas nuevas: la sincronización baja estados. */
    fun onSendNow() {
        if (sending.value) return
        sending.value = true
        viewModelScope.launch {
            syncPending(SyncTrigger.MANUAL)
            sending.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Distintivo de la pestaña: respuestas del agrónomo que el productor no ha
 *  abierto. Una respuesta que pasa desapercibida es como no haber respondido. */
@HiltViewModel
class ReviewBadgeViewModel @Inject constructor(
    reviews: ReviewRequestRepository,
) : ViewModel() {
    val unread: StateFlow<Int> = reviews.observeUnreadResolutions()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)
}
