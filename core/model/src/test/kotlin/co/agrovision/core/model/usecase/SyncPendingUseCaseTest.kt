package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.identity.UserTier
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ConnectivityMonitor
import co.agrovision.core.model.port.ConnectivityStatus
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.port.SyncGateway
import co.agrovision.core.model.port.UploadOutcome
import co.agrovision.core.model.port.UploadStatus
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.RemoteReviewStatus
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewStatus
import co.agrovision.core.model.settings.UserPreferences
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Subida de Solicitudes de Revisión con una URL prefirmada por foto, sesión
 * vencida y lectura del estado que les dio el servidor.
 *
 * Las reglas que protegen:
 *  - Una solicitud NO se marca enviada mientras le falte una foto. Si se marca,
 *    el productor cree que un agrónomo la está viendo y el agrónomo recibe una
 *    solicitud que no puede resolver.
 *  - Un 401 no es culpa del lote: la cola espera intacta, sin gastar reintentos
 *    ni terminar en FAILED, hasta que el productor vuelva a entrar.
 */
class SyncPendingUseCaseTest {

    private val review = ReviewRequest(
        id = "req-1",
        captureId = null,
        images = listOf(image("img-1", "/fotos/1.jpg"), image("img-2", "/fotos/2.jpg")),
        cropType = CropType.CAFE,
        plantOrgan = PlantOrgan.HOJA,
        noteText = null,
        noteAudioPath = null,
        location = null,
        createdAtEpochMillis = 100L,
        status = ReviewStatus.PENDING_UPLOAD,
        resolution = null,
        sync = SyncStatus.PENDING,
    )

    private val captures = mockk<CaptureRepository>(relaxed = true).also {
        coEvery { it.nextBatchToUpload(any(), any()) } returns emptyList()
    }
    private val reviews = mockk<ReviewRequestRepository>(relaxed = true).also {
        coEvery { it.nextBatchToUpload(any(), any()) } returns listOf(review)
        coEvery { it.statusCursor() } returns null
        coEvery { it.mergeRemoteStatuses(any()) } returns 0
    }
    private val gateway = FakeGateway()
    private var currentAccount = Account.anonymous("device").copy(tier = UserTier.T2_VALIDADO)
    private val account = mockk<AccountRepository>().also {
        coEvery { it.current() } answers { currentAccount }
        coJustRun { it.markSessionExpired() }
    }
    private val settings = mockk<SettingsRepository>().also {
        coEvery { it.current() } returns UserPreferences.DEFAULT
    }
    private val connectivity = mockk<ConnectivityMonitor>().also {
        every { it.current() } returns ConnectivityStatus(isOnline = true, isMetered = false)
    }

    private val sync = SyncPendingUseCase(
        captures = captures,
        reviews = reviews,
        gateway = gateway,
        account = account,
        settings = settings,
        connectivity = connectivity,
        clock = Clock { NOW },
    )

    // ── Una URL por foto ─────────────────────────────────────────────────────

    @Test
    fun `cada foto se sube a su propia URL y la solicitud queda enviada`() = runTest {
        respondWith(outcome(UploadStatus.ACCEPTED, "img-1" to "https://s3/1", "img-2" to "https://s3/2"))

        val summary = runSync()

        assertEquals(
            listOf("https://s3/1" to "/fotos/1.jpg", "https://s3/2" to "/fotos/2.jpg"),
            gateway.uploadedImages,
        )
        verifySubmitted()
        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `si falla una foto la solicitud no se marca enviada y se reintenta`() = runTest {
        respondWith(outcome(UploadStatus.ACCEPTED, "img-1" to "https://s3/1", "img-2" to "https://s3/2"))
        gateway.failingUrls += "https://s3/2"
        val error = slot<String>()

        val summary = runSync()

        verifyNeverSubmitted()
        coVerify {
            reviews.updateSyncState(
                id = "req-1",
                state = SyncState.PENDING,
                serverId = "srv-1",
                error = capture(error),
                nextAttemptAtEpochMillis = more(NOW),
                status = null,
            )
        }
        assertTrue(error.captured.contains("img-2"), error.captured)
        assertEquals(0, summary.uploaded)
        assertEquals(1, summary.failed)
    }

    @Test
    fun `aceptada sin URL para una foto no cuenta como enviada`() = runTest {
        respondWith(outcome(UploadStatus.ACCEPTED, "img-1" to "https://s3/1"))

        runSync()

        verifyNeverSubmitted()
        coVerify { reviews.updateSyncState("req-1", SyncState.PENDING, "srv-1", any(), any(), null) }
    }

    @Test
    fun `duplicada con fotos pendientes las sube y queda enviada`() = runTest {
        // Un intento anterior subió img-1 y falló con img-2: el servidor solo
        // devuelve URL para la que le falta.
        respondWith(outcome(UploadStatus.DUPLICATE, "img-2" to "https://s3/2"))

        runSync()

        assertEquals(listOf("https://s3/2" to "/fotos/2.jpg"), gateway.uploadedImages)
        verifySubmitted()
    }

    @Test
    fun `duplicada cuya foto pendiente vuelve a fallar sigue en la cola`() = runTest {
        respondWith(outcome(UploadStatus.DUPLICATE, "img-2" to "https://s3/2"))
        gateway.failingUrls += "https://s3/2"

        runSync()

        verifyNeverSubmitted()
        coVerify { reviews.updateSyncState("req-1", SyncState.PENDING, "srv-1", any(), any(), null) }
    }

    @Test
    fun `duplicada sin imageUploads significa que el servidor ya tiene todas las fotos`() = runTest {
        respondWith(outcome(UploadStatus.DUPLICATE))

        runSync()

        assertTrue(gateway.uploadedImages.isEmpty())
        verifySubmitted()
    }

    // ── Sesión vencida (401) ─────────────────────────────────────────────────

    @Test
    fun `un 401 al enviar el lote deja la cola intacta y pide iniciar sesion`() = runTest {
        gateway.reviewUploadError = DomainError.NotSignedIn

        val summary = runSync()

        assertEquals(SkipReason.SIGN_IN_REQUIRED, summary.skipReason)
        assertEquals(0, summary.failed)
        coVerify(exactly = 1) { account.markSessionExpired() }
        // Ni reintento gastado ni FAILED: la fila no se toca.
        coVerify(exactly = 0) { reviews.updateSyncState(any(), any(), any(), any(), any(), any()) }
        // Las capturas van primero y aquí no había ninguna; con el token vencido no se
        // sigue pidiendo el estado de las solicitudes.
        assertTrue(gateway.requestedCursors.isEmpty())
    }

    @Test
    fun `un 401 al subir una foto tampoco gasta reintentos`() = runTest {
        respondWith(outcome(UploadStatus.ACCEPTED, "img-1" to "https://s3/1", "img-2" to "https://s3/2"))
        gateway.unauthorizedUrls += "https://s3/1"

        val summary = runSync()

        assertEquals(SkipReason.SIGN_IN_REQUIRED, summary.skipReason)
        coVerify(exactly = 1) { account.markSessionExpired() }
        coVerify(exactly = 0) { reviews.updateSyncState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un 401 al subir capturas las devuelve a la cola sin contar el intento`() = runTest {
        coEvery { reviews.nextBatchToUpload(any(), any()) } returns emptyList()
        coEvery { captures.nextBatchToUpload(any(), any()) } returns listOf(mockk<Capture>(relaxed = true) {
            every { id } returns "cap-1"
            every { sync } returns SyncStatus(SyncState.PENDING, attempts = 3, lastError = "antes")
        })
        gateway.captureUploadError = DomainError.NotSignedIn

        val summary = runSync()

        assertEquals(SkipReason.SIGN_IN_REQUIRED, summary.skipReason)
        coVerify {
            captures.updateSyncState(
                id = "cap-1",
                state = SyncState.PENDING,
                serverId = null,
                error = "antes",
                nextAttemptAtEpochMillis = null,
                countsAsAttempt = false,
            )
        }
        coVerify(exactly = 0) { captures.updateSyncState("cap-1", SyncState.FAILED, any(), any(), any(), any()) }
    }

    @Test
    fun `con la sesion vencida no se intenta subir nada`() = runTest {
        currentAccount = currentAccount.copy(sessionExpired = true)

        val summary = runSync()

        assertEquals(SkipReason.SIGN_IN_REQUIRED, summary.skipReason)
        assertEquals(0, gateway.reviewUploadCalls)
        coVerify(exactly = 0) { reviews.nextBatchToUpload(any(), any()) }
    }

    // ── Estado de mis solicitudes ────────────────────────────────────────────

    @Test
    fun `el estado remoto se fusiona y el cursor avanza al mayor updatedAt`() = runTest {
        respondWith(outcome(UploadStatus.DUPLICATE))
        val statuses = listOf(
            RemoteReviewStatus("req-1", ReviewStatus.ASSIGNED, null, updatedAtEpochMillis = 900L),
            RemoteReviewStatus("req-0", ReviewStatus.SUBMITTED, null, updatedAtEpochMillis = 500L),
        )
        gateway.statuses = statuses
        coEvery { reviews.mergeRemoteStatuses(statuses) } returns 1

        val summary = runSync()

        assertEquals(listOf<Long?>(null), gateway.requestedCursors)
        coVerify { reviews.updateStatusCursor(900L) }
        assertEquals(1, summary.statusesUpdated)
    }

    @Test
    fun `sin updatedAt el cursor no avanza y se pide la lista completa`() = runTest {
        respondWith(outcome(UploadStatus.DUPLICATE))
        gateway.statuses =
            listOf(RemoteReviewStatus("req-1", ReviewStatus.SUBMITTED, null, updatedAtEpochMillis = null))

        runSync()

        coVerify(exactly = 0) { reviews.updateStatusCursor(any()) }
    }

    @Test
    fun `un 401 al pedir estados tambien vence la sesion`() = runTest {
        respondWith(outcome(UploadStatus.DUPLICATE))
        gateway.statusesError = DomainError.NotSignedIn

        val summary = runSync()

        assertEquals(SkipReason.SIGN_IN_REQUIRED, summary.skipReason)
        coVerify(exactly = 1) { account.markSessionExpired() }
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private suspend fun runSync(): SyncSummary =
        (sync(SyncTrigger.MANUAL) as AgroResult.Success).value

    private fun verifySubmitted() = coVerify {
        reviews.updateSyncState("req-1", SyncState.UPLOADED, "srv-1", null, null, ReviewStatus.SUBMITTED)
    }

    private fun verifyNeverSubmitted() = coVerify(exactly = 0) {
        reviews.updateSyncState(any(), any(), any(), any(), any(), ReviewStatus.SUBMITTED)
    }

    private fun respondWith(outcome: UploadOutcome) {
        gateway.reviewOutcomes = listOf(outcome)
    }

    private fun outcome(status: UploadStatus, vararg urls: Pair<String, String>) = UploadOutcome(
        localId = "req-1",
        serverId = "srv-1",
        status = status,
        presignedImageUrl = null,
        rejectionReason = null,
        imageUploadUrls = urls.toMap(),
    )

    /**
     * A mano y no con mockk: mockk no sabe desempaquetar `AgroResult` —una value
     * class genérica— como retorno de una función suspendida y lanza
     * ClassCastException.
     */
    private class FakeGateway : SyncGateway {
        var reviewOutcomes: List<UploadOutcome> = emptyList()
        var reviewUploadError: DomainError? = null
        var reviewUploadCalls = 0
        var captureUploadError: DomainError? = null
        var statuses: List<RemoteReviewStatus> = emptyList()
        var statusesError: DomainError? = null
        val failingUrls = mutableSetOf<String>()
        val unauthorizedUrls = mutableSetOf<String>()
        val uploadedImages = mutableListOf<Pair<String, String>>()
        val requestedCursors = mutableListOf<Long?>()

        override suspend fun uploadReviewRequests(
            batch: List<ReviewRequest>,
        ): AgroResult<List<UploadOutcome>, DomainError> {
            reviewUploadCalls++
            return reviewUploadError?.let { AgroResult.failure(it) } ?: AgroResult.success(reviewOutcomes)
        }

        override suspend fun uploadCaptures(
            batch: List<Capture>,
        ): AgroResult<List<UploadOutcome>, DomainError> =
            captureUploadError?.let { AgroResult.failure(it) } ?: AgroResult.success(emptyList())

        override suspend fun uploadImage(
            presignedUrl: String,
            localPath: String,
        ): AgroResult<Unit, DomainError> = when (presignedUrl) {
            in unauthorizedUrls -> AgroResult.failure(DomainError.NotSignedIn)
            in failingUrls -> AgroResult.failure(DomainError.NoConnection)
            else -> {
                uploadedImages += presignedUrl to localPath
                AgroResult.unit
            }
        }

        override suspend fun fetchMyResolutions(
            sinceEpochMillis: Long?,
        ): AgroResult<List<RemoteReviewStatus>, DomainError> {
            requestedCursors += sinceEpochMillis
            return statusesError?.let { AgroResult.failure(it) } ?: AgroResult.success(statuses)
        }

        override suspend fun fetchCatalogDelta(sinceEpochMillis: Long?, etag: String?) = unused()
        override suspend fun registerPushToken(token: String) = unused()
        override suspend fun postFieldMetrics(metrics: Map<String, String>) = unused()
        override suspend fun fetchModelManifest(appVersion: String, deviceId: String) = unused()

        private fun unused(): Nothing = error("No lo usa esta prueba")
    }

    private companion object {
        const val NOW = 1_000L

        fun image(id: String, path: String) = ReviewImage(
            id = id,
            imagePath = path,
            thumbnailPath = "$path.thumb",
            angle = CaptureAngle.GENERAL,
            embedding = null,
        )
    }
}
