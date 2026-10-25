package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.identity.UploadGate
import co.agrovision.core.model.identity.withFarmContext
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ConnectivityMonitor
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.port.SyncGateway
import co.agrovision.core.model.port.UploadOutcome
import co.agrovision.core.model.port.UploadStatus
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.pow
import javax.inject.Inject

/**
 * Selecciona un lote por prioridad y antigüedad, lo sube y concilia estados.
 *
 * **Prioridad de cola (§9.4), y el orden importa:**
 *   1. Solicitudes de Revisión — son lo único que el productor está esperando
 *      que alguien responda. Retrasarlas alarga la latencia de resolución, que
 *      es el indicador anticipado del colapso del ciclo (§12.3).
 *   2. Correcciones del productor — dato de entrenamiento premium.
 *   3. Capturas confirmadas — valiosas en volumen, no urgentes una a una.
 *
 * Es idempotente de arriba abajo: cada elemento viaja con su UUID como clave de
 * idempotencia, y el estado `DUPLICATE` que devuelve el servidor no es un error
 * sino la confirmación de que ya estaba a salvo.
 */
class SyncPendingUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val reviews: ReviewRequestRepository,
    private val gateway: SyncGateway,
    private val account: AccountRepository,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityMonitor,
    private val clock: Clock,
) {

    /** Una sola sincronización a la vez en todo el proceso: el disparador de primer
     *  plano y el worker pueden coincidir, y dos lotes iguales en vuelo gastarían
     *  datos del productor y se pisarían los estados. */
    suspend operator fun invoke(trigger: SyncTrigger): AgroResult<SyncSummary, DomainError> =
        running.withLock { run(trigger) }

    private suspend fun run(trigger: SyncTrigger): AgroResult<SyncSummary, DomainError> {
        // Con el cerrojo tomado nada puede estar subiendo de verdad: lo que siga en
        // UPLOADING es de un proceso que murió a mitad del envío.
        captures.requeueStuckUploads()

        val status = connectivity.current()
        if (!status.isOnline) return AgroResult.failure(DomainError.NoConnection)

        // ADR-008: la decisión sobre el gasto de datos le corresponde a quien lo
        // paga. No se impone red no medida; se respeta lo que el productor eligió.
        val preferences = settings.current()
        // Revocar el consentimiento retira del envío lo que aún no salió del teléfono
        if (!preferences.trainingConsent) captures.skipUnsent()
        if (status.isMetered && !preferences.syncNetworkPolicy.allowsMetered) {
            return AgroResult.success(SyncSummary.skipped(SkipReason.METERED_NETWORK))
        }

        // La compuerta se evalúa AQUÍ, al sincronizar, nunca al crear (§6.3).
        // Lo que está en cola no se pierde: espera a que la cuenta se valide.
        val current = account.current()
        val gate = UploadGate.evaluate(
            tier = current.tier,
            queuedCount = 0,
            registeredAtEpochMillis = current.registeredAtEpochMillis,
            sessionExpired = current.sessionExpired,
        )
        if (gate is UploadGate.BlockedSignInRequired) {
            return AgroResult.success(SyncSummary.skipped(SkipReason.SIGN_IN_REQUIRED))
        }
        if (gate.isBlocked) {
            return AgroResult.success(SyncSummary.skipped(SkipReason.UPLOAD_GATE))
        }

        val now = clock.nowEpochMillis()
        var uploaded = 0
        var failed = 0

        // ── 1 y 2. Capturas y solicitudes de revisión ──
        // Las solicitudes guardan la versión del modelo de su captura. Por eso las capturas
        // se sincronizan primero: el servidor puede resolver el captureId antes de crear la
        // solicitud y evitar que el panel la muestre como "Sin versión".
        // `nextBatchToUpload` ya devuelve las correcciones primero: el orden vive
        // en la consulta SQL, no en un `sortedBy` en memoria sobre una lista que
        // podría tener miles de filas.
        val captureBatch = captures.nextBatchToUpload(AgroConstants.Sync.BATCH_SIZE, now)
        if (captureBatch.isNotEmpty()) {
            captureBatch.forEach { captures.updateSyncState(it.id, SyncState.UPLOADING) }
            when (val result = gateway.uploadCaptures(captureBatch)) {
                is AgroResult.Failure -> {
                    if (result.error == DomainError.NotSignedIn) {
                        // De vuelta a la cola tal como estaban antes de marcarlas
                        // UPLOADING, sin contar el intento.
                        captureBatch.forEach { capture ->
                            captures.updateSyncState(
                                id = capture.id,
                                state = SyncState.PENDING,
                                error = capture.sync.lastError,
                                nextAttemptAtEpochMillis = capture.sync.nextAttemptAtEpochMillis,
                                countsAsAttempt = false,
                            )
                        }
                        return signInRequired(trigger, uploaded, failed)
                    }
                    captureBatch.forEach { capture ->
                        failed++
                        captures.updateSyncState(
                            id = capture.id,
                            state = retryStateFor(capture.sync.attempts),
                            error = describe(result.error),
                            nextAttemptAtEpochMillis = now + backoffMillis(capture.sync.attempts),
                        )
                    }
                }
                is AgroResult.Success -> uploaded += reconcileCaptures(captureBatch, result.value, now)
            }
        }

        // ── 3. Solicitudes de revisión ──
        val reviewBatch = reviews.nextBatchToUpload(AgroConstants.Sync.BATCH_SIZE, now)
        if (reviewBatch.isNotEmpty()) {
            // Variedad y edad del cafetal viajan en la nota (ver `withFarmContext`).
            val outgoing = reviewBatch.map { review ->
                review.withFarmContext(current).copy(
                    modelVersion = review.captureId?.let { captures.findById(it)?.modelVersion },
                )
            }
            when (val result = gateway.uploadReviewRequests(outgoing)) {
                is AgroResult.Failure -> {
                    if (result.error == DomainError.NotSignedIn) {
                        return signInRequired(trigger, uploaded, failed)
                    }
                    reviewBatch.forEach { review ->
                        failed++
                        retryReview(review, describe(result.error), now)
                    }
                }
                is AgroResult.Success -> {
                    val reconciled = reconcileReviews(reviewBatch, result.value, now)
                    uploaded += reconciled.submitted
                    failed += reconciled.failed
                    if (reconciled.sessionExpired) return signInRequired(trigger, uploaded, failed)
                }
            }
        }

        // ── 4. Estado de mis solicitudes ──
        // Después de subir, para que una solicitud recién enviada ya aparezca con
        // el estado que le dio el servidor.
        val statusesUpdated = when (val refreshed = refreshReviewStatuses()) {
            is AgroResult.Success -> refreshed.value
            is AgroResult.Failure ->
                if (refreshed.error == DomainError.NotSignedIn) {
                    return signInRequired(trigger, uploaded, failed)
                } else {
                    0
                }
        }

        return AgroResult.success(
            SyncSummary(
                trigger = trigger,
                uploaded = uploaded,
                failed = failed,
                skipReason = null,
                statusesUpdated = statusesUpdated,
            )
        )
    }

    /**
     * El servidor rechazó el JWT. Se borra y la cuenta pasa a «requiere iniciar
     * sesión»; la cola queda intacta y sube cuando el productor vuelva a entrar.
     */
    private suspend fun signInRequired(
        trigger: SyncTrigger,
        uploaded: Int,
        failed: Int,
    ): AgroResult<SyncSummary, DomainError> {
        account.markSessionExpired()
        return AgroResult.success(
            SyncSummary(
                trigger = trigger,
                uploaded = uploaded,
                failed = failed,
                skipReason = SkipReason.SIGN_IN_REQUIRED,
            )
        )
    }

    private suspend fun reconcileCaptures(
        batch: List<co.agrovision.core.model.capture.Capture>,
        outcomes: List<UploadOutcome>,
        now: Long,
    ): Int {
        val byId = outcomes.associateBy { it.localId }
        var uploaded = 0

        batch.forEach { capture ->
            when (val outcome = byId[capture.id]) {
                null -> captures.updateSyncState(
                    id = capture.id,
                    state = retryStateFor(capture.sync.attempts),
                    error = "El servidor no devolvió resultado para esta captura.",
                    nextAttemptAtEpochMillis = now + backoffMillis(capture.sync.attempts),
                )

                else -> when (outcome.status) {
                    UploadStatus.ACCEPTED -> {
                        outcome.presignedImageUrl?.let { url ->
                            gateway.uploadImage(url, capture.imagePath)
                        }
                        captures.updateSyncState(capture.id, SyncState.UPLOADED, outcome.serverId)
                        uploaded++
                    }
                    // Idempotencia funcionando: el servidor ya lo tenía.
                    UploadStatus.DUPLICATE -> {
                        captures.updateSyncState(capture.id, SyncState.UPLOADED, outcome.serverId)
                        uploaded++
                    }
                    // Rechazo definitivo: reintentar no cambiaría nada, y dejarla
                    // en PENDING la haría reintentarse para siempre.
                    UploadStatus.REJECTED -> captures.updateSyncState(
                        id = capture.id,
                        state = SyncState.FAILED,
                        error = outcome.rejectionReason,
                    )
                }
            }
        }
        return uploaded
    }

    /**
     * Una solicitud solo pasa a enviada cuando el servidor aceptó los metadatos
     * **y** llegaron todas sus fotos. Marcarla antes le diría al productor que un
     * agrónomo la está viendo cuando al agrónomo le faltan las imágenes. Al subir
     * la última foto es el servidor quien la pasa a SUBMITTED.
     *
     * Si la sesión vence a mitad del lote se detiene ahí: las solicitudes que
     * faltan quedan como estaban, sin gastar reintentos.
     */
    private suspend fun reconcileReviews(
        batch: List<ReviewRequest>,
        outcomes: List<UploadOutcome>,
        now: Long,
    ): ReviewReconciliation {
        val byId = outcomes.associateBy { it.localId }
        var submitted = 0
        var failed = 0
        for (review in batch) {
            val outcome = byId[review.id]
            when {
                outcome == null -> {
                    failed++
                    retryReview(review, "El servidor no devolvió resultado para esta solicitud.", now)
                }

                // Rechazo definitivo: reintentar no cambiaría nada.
                outcome.status == UploadStatus.REJECTED -> {
                    failed++
                    reviews.updateSyncState(
                        id = review.id,
                        state = SyncState.FAILED,
                        error = outcome.rejectionReason,
                    )
                }

                else -> when (val images = uploadReviewImages(review, outcome)) {
                    ImageUpload.Done -> {
                        reviews.updateSyncState(
                            id = review.id,
                            state = SyncState.UPLOADED,
                            serverId = outcome.serverId,
                            status = ReviewStatus.SUBMITTED,
                        )
                        submitted++
                    }
                    is ImageUpload.Failed -> {
                        failed++
                        // El serverId se guarda igual: los metadatos ya están en
                        // el servidor y el reintento llegará como DUPLICATE con
                        // las fotos que falten.
                        retryReview(review, images.reason, now, outcome.serverId)
                    }
                    ImageUpload.SessionExpired ->
                        return ReviewReconciliation(submitted, failed, sessionExpired = true)
                }
            }
        }
        return ReviewReconciliation(submitted, failed, sessionExpired = false)
    }

    /**
     * Sube cada foto a SU URL prefirmada.
     *
     * Las URL de `imageUploads` son exactamente las fotos que al servidor le
     * faltan, en ACCEPTED y en DUPLICATE:
     * - ACCEPTED: le faltan todas, así que cada foto tiene que traer URL. Una
     *   que no la traiga es una foto que nunca llegaría: cuenta como fallo.
     * - DUPLICATE: trae las que no llegaron en un intento anterior. Si no trae
     *   ninguna, el servidor ya las tiene todas.
     *
     * Se detiene en el primer fallo: el reintento vuelve como DUPLICATE con las
     * que falten, y seguir gastaría datos del productor en un intento que ya no
     * cuenta.
     */
    private suspend fun uploadReviewImages(review: ReviewRequest, outcome: UploadOutcome): ImageUpload {
        for (image in review.images) {
            val url = outcome.imageUploadUrls[image.id]
            if (url == null) {
                if (outcome.status == UploadStatus.DUPLICATE) continue
                return ImageUpload.Failed("El servidor no devolvió URL de subida para la foto ${image.id}.")
            }
            val result = gateway.uploadImage(url, image.imagePath)
            if (result is AgroResult.Failure) {
                if (result.error == DomainError.NotSignedIn) return ImageUpload.SessionExpired
                return ImageUpload.Failed("Foto ${image.id}: ${describe(result.error)}")
            }
        }
        return ImageUpload.Done
    }

    private data class ReviewReconciliation(
        val submitted: Int,
        val failed: Int,
        val sessionExpired: Boolean,
    )

    private sealed interface ImageUpload {
        data object Done : ImageUpload
        data class Failed(val reason: String) : ImageUpload
        data object SessionExpired : ImageUpload
    }

    private suspend fun retryReview(
        review: ReviewRequest,
        error: String,
        now: Long,
        serverId: String? = null,
    ) = reviews.updateSyncState(
        id = review.id,
        state = retryStateFor(review.sync.attempts),
        serverId = serverId,
        error = error,
        nextAttemptAtEpochMillis = now + backoffMillis(review.sync.attempts),
    )

    /**
     * Trae el estado de las solicitudes y lo fusiona en Room. Solo devuelve el
     * fallo de sesión vencida; cualquier otro no cuenta como fallo de subida: lo
     * subido ya está a salvo y el estado se vuelve a pedir en la siguiente
     * sincronización.
     */
    private suspend fun refreshReviewStatuses(): AgroResult<Int, DomainError> {
        val cursor = reviews.statusCursor()
        val statuses = when (val result = gateway.fetchMyResolutions(cursor)) {
            is AgroResult.Failure ->
                return if (result.error == DomainError.NotSignedIn) result else AgroResult.success(0)
            is AgroResult.Success -> result.value
        }
        val merged = reviews.mergeRemoteStatuses(statuses)

        // El siguiente `since` es el MAYOR `updatedAt` recibido: hora del
        // servidor, nunca la del teléfono. Sin `updatedAt` se pide la lista
        // completa cada vez: son las solicitudes de un solo productor.
        statuses.mapNotNull { it.updatedAtEpochMillis }.maxOrNull()
            ?.takeIf { cursor == null || it > cursor }
            ?.let { reviews.updateStatusCursor(it) }
        return AgroResult.success(merged)
    }

    /**
     * Tras agotar los reintentos la fila pasa a FAILED y deja de reintentarse en
     * automático — pero sigue visible y reintentable a mano. Una captura nunca
     * desaparece en silencio.
     */
    private fun retryStateFor(attempts: Int): SyncState =
        if (attempts + 1 >= AgroConstants.Sync.MAX_ATTEMPTS) SyncState.FAILED else SyncState.PENDING

    private fun backoffMillis(attempts: Int): Long {
        val base = AgroConstants.Sync.INITIAL_BACKOFF.inWholeMilliseconds
        val grown = base * 2.0.pow(attempts.coerceAtMost(MAX_BACKOFF_EXPONENT))
        return min(grown.toLong(), AgroConstants.Sync.MAX_BACKOFF.inWholeMilliseconds)
    }

    private fun describe(error: DomainError): String = when (error) {
        is DomainError.RemoteFailure -> "HTTP ${error.httpCode}: ${error.detail.orEmpty()}"
        DomainError.NoConnection -> "Sin conexión"
        DomainError.NotSignedIn -> "Sesión vencida"
        else -> error::class.simpleName.orEmpty()
    }

    private companion object {
        const val MAX_BACKOFF_EXPONENT = 10
        val running = Mutex()
    }
}

enum class SyncTrigger {
    /** Disparador primario. Al abrir la app y al volver del segundo plano. Es la
     *  ruta que SIEMPRE funciona, independientemente del fabricante (§9.4). */
    FOREGROUND,

    /** Disparador secundario. Optimización: si el administrador de batería del
     *  fabricante lo suprime, no se pierde nada. */
    WORKER,

    /** Disparador terciario. Lotes grandes tras días sin señal, iniciado por una
     *  acción visible del productor. */
    FOREGROUND_SERVICE,

    MANUAL,
}

data class SyncSummary(
    val trigger: SyncTrigger,
    val uploaded: Int,
    val failed: Int,
    val skipReason: SkipReason?,
    /** Solicitudes cuyo estado o resolución cambió en esta sincronización. */
    val statusesUpdated: Int = 0,
) {
    companion object {
        fun skipped(reason: SkipReason) = SyncSummary(SyncTrigger.MANUAL, 0, 0, reason)
    }
}

enum class SkipReason {
    METERED_NETWORK,
    UPLOAD_GATE,

    /** El JWT venció (401). La cola espera intacta a que el productor vuelva a
     *  entrar con OTP. */
    SIGN_IN_REQUIRED,
    NOTHING_PENDING,
}
