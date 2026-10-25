package co.agrovision.core.data.repository

import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.common.Float16Codec
import co.agrovision.core.database.dao.ReviewDao
import co.agrovision.core.database.dao.SyncCursorDao
import co.agrovision.core.database.entity.ReviewImageEntity
import co.agrovision.core.database.entity.ReviewRequestEntity
import co.agrovision.core.database.entity.ReviewRequestWithImages
import co.agrovision.core.database.entity.SyncCursorEntity
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.RemoteReviewStatus
import co.agrovision.core.model.review.ResolutionType
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewResolution
import co.agrovision.core.model.review.ReviewStatus
import co.agrovision.core.model.scan.Embedding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewRequestRepositoryImpl @Inject constructor(
    private val dao: ReviewDao,
    private val cursors: SyncCursorDao,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : ReviewRequestRepository {

    override fun observeMine(): Flow<List<ReviewRequest>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<ReviewRequest?> =
        dao.observeById(id).map { it?.toDomain() }

    override fun observeUnreadResolutions(): Flow<List<ReviewRequest>> =
        dao.observeUnreadResolutions().map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(request: ReviewRequest): AgroResult<Unit, DomainError> =
        withContext(io) {
            try {
                // Transaccional: solicitud e imágenes se escriben juntas. Una
                // solicitud sin sus tomas es una solicitud que el agrónomo no
                // puede resolver, y ocuparía su tiempo —el recurso más escaso del
                // sistema— para acabar en «inutilizable».
                dao.insertWithImages(
                    request = request.toEntity(),
                    images = request.images.map { it.toEntity(request.id) },
                )
                AgroResult.unit
            } catch (error: Exception) {
                AgroResult.failure(DomainError.PersistenceFailed(error.message.orEmpty()))
            }
        }

    override suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<ReviewRequest> =
        withContext(io) {
            dao.nextBatchToUpload(limit, nowEpochMillis).map { it.toDomain() }
        }

    override suspend fun updateSyncState(
        id: String,
        state: SyncState,
        serverId: String?,
        error: String?,
        nextAttemptAtEpochMillis: Long?,
        status: ReviewStatus?,
    ) = withContext(io) {
        dao.updateSyncState(id, state.name, serverId, error, nextAttemptAtEpochMillis, status?.name)
    }

    /**
     * Las solicitudes que el servidor conoce y el teléfono no —otra instalación,
     * datos borrados— se ignoran: sin sus fotos locales no hay nada que mostrar.
     */
    override suspend fun mergeRemoteStatuses(statuses: List<RemoteReviewStatus>): Int =
        withContext(io) {
            statuses.sumOf { remote ->
                dao.applyRemoteStatus(
                    id = remote.requestId,
                    status = remote.status.name,
                    resolutionType = remote.resolution?.type?.name,
                    resolvedPestId = remote.resolution?.resolvedPestId,
                    agronomistResponse = remote.resolution?.agronomistResponse,
                    resolvedAt = remote.resolution?.resolvedAtEpochMillis,
                )
            }
        }

    override suspend fun statusCursor(): Long? = withContext(io) {
        cursors.find(SyncCursorEntity.RESOURCE_RESOLUTIONS)?.sinceEpochMillis
    }

    override suspend fun updateStatusCursor(sinceEpochMillis: Long) = withContext(io) {
        cursors.upsert(
            SyncCursorEntity(
                resource = SyncCursorEntity.RESOURCE_RESOLUTIONS,
                sinceEpochMillis = sinceEpochMillis,
                etag = null,
            )
        )
    }

    override suspend fun markResolutionRead(id: String) = withContext(io) {
        dao.markResolutionRead(id)
    }
}

// ── Mapeo ────────────────────────────────────────────────────────────────────

internal fun ReviewRequestWithImages.toDomain(): ReviewRequest = ReviewRequest(
    id = request.id,
    captureId = request.captureId,
    images = images.map { image ->
        ReviewImage(
            id = image.id,
            imagePath = image.imagePath,
            thumbnailPath = image.thumbnailPath,
            angle = CaptureAngle.entries.firstOrNull { it.name == image.angle }
                ?: CaptureAngle.GENERAL,
            embedding = image.embedding?.let { Embedding(Float16Codec.decode(it)) },
        )
    },
    cropType = CropType.entries.firstOrNull { it.name == request.cropType } ?: CropType.CAFE,
    plantOrgan = PlantOrgan.entries.firstOrNull { it.name == request.plantOrgan }
        ?: PlantOrgan.HOJA,
    noteText = request.noteText,
    noteAudioPath = request.noteAudioPath,
    location = request.latitude?.let { lat ->
        request.longitude?.let { lon -> GeoPoint(lat, lon, request.locationAccuracyM) }
    },
    createdAtEpochMillis = request.createdAt,
    status = ReviewStatus.entries.firstOrNull { it.name == request.status }
        ?: ReviewStatus.PENDING_UPLOAD,
    resolution = request.resolutionType?.let { type ->
        ReviewResolution(
            type = ResolutionType.entries.firstOrNull { it.name == type }
                ?: ResolutionType.UNUSABLE,
            resolvedPestId = request.resolvedPestId,
            agronomistResponse = request.agronomistResponse.orEmpty(),
            resolvedAtEpochMillis = request.resolvedAt ?: 0L,
        )
    },
    sync = SyncStatus(
        state = SyncState.entries.firstOrNull { it.name == request.syncState }
            ?: SyncState.PENDING,
        attempts = request.syncAttempts,
        lastError = request.lastSyncError,
        serverId = request.serverId,
        nextAttemptAtEpochMillis = request.nextAttemptAt,
    ),
)

internal fun ReviewRequest.toEntity(): ReviewRequestEntity = ReviewRequestEntity(
    id = id,
    captureId = captureId,
    cropType = cropType.name,
    plantOrgan = plantOrgan.name,
    noteText = noteText,
    noteAudioPath = noteAudioPath,
    latitude = location?.latitude,
    longitude = location?.longitude,
    locationAccuracyM = location?.accuracyMeters,
    createdAt = createdAtEpochMillis,
    status = status.name,
    resolutionType = resolution?.type?.name,
    resolvedPestId = resolution?.resolvedPestId,
    agronomistResponse = resolution?.agronomistResponse,
    resolvedAt = resolution?.resolvedAtEpochMillis,
    // Al llegar una resolución nueva se marca como NO leída: el distintivo de la
    // pestaña es lo único que le avisa al productor de que alguien le respondió, y
    // una respuesta que pasa desapercibida equivale a no haber respondido (§1.2).
    resolutionRead = false,
    syncState = sync.state.name,
    syncAttempts = sync.attempts,
    lastSyncError = sync.lastError,
    nextAttemptAt = sync.nextAttemptAtEpochMillis,
    serverId = sync.serverId,
)

internal fun ReviewImage.toEntity(requestId: String): ReviewImageEntity = ReviewImageEntity(
    id = id,
    reviewRequestId = requestId,
    imagePath = imagePath,
    thumbnailPath = thumbnailPath,
    angle = angle.name,
    embedding = embedding?.let { Float16Codec.encode(it.values) },
)
