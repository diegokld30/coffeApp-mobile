package co.agrovision.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import co.agrovision.core.database.entity.ReviewImageEntity
import co.agrovision.core.database.entity.ReviewRequestEntity
import co.agrovision.core.database.entity.ReviewRequestWithImages
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {

    @Transaction
    @Query("SELECT * FROM review_requests ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ReviewRequestWithImages>>

    @Transaction
    @Query("SELECT * FROM review_requests WHERE id = :id")
    fun observeById(id: String): Flow<ReviewRequestWithImages?>

    /**
     * Resoluciones que el productor todavía no ha visto.
     *
     * Alimenta el distintivo de la pestaña. Que una respuesta de agrónomo llegue
     * y pase desapercibida es lo mismo que no haber respondido: el productor
     * concluye que nadie le contestó y deja de enviar solicitudes (§1.2).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM review_requests
        WHERE status = 'RESOLVED' AND resolution_read = 0
        ORDER BY resolved_at DESC
        """
    )
    fun observeUnreadResolutions(): Flow<List<ReviewRequestWithImages>>

    @Transaction
    @Query(
        """
        SELECT * FROM review_requests
        WHERE sync_state = 'PENDING'
          AND (next_attempt_at IS NULL OR next_attempt_at <= :nowEpochMillis)
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<ReviewRequestWithImages>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(request: ReviewRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImages(images: List<ReviewImageEntity>)

    @Upsert
    suspend fun upsertRequest(request: ReviewRequestEntity)

    /** Solicitud e imágenes se escriben o no se escriben. Una solicitud sin sus
     *  imágenes es una solicitud que el agrónomo no puede resolver. */
    @Transaction
    suspend fun insertWithImages(request: ReviewRequestEntity, images: List<ReviewImageEntity>) {
        insertRequest(request)
        insertImages(images)
    }

    /** Mismo criterio que `CaptureDao.updateSyncState`: cada paso a PENDING o
     *  FAILED cuenta como intento, para que el backoff crezca. */
    @Query(
        """
        UPDATE review_requests SET
            sync_state = :state,
            server_id = COALESCE(:serverId, server_id),
            last_sync_error = :error,
            next_attempt_at = :nextAttemptAt,
            sync_attempts = CASE WHEN :state = 'PENDING' OR :state = 'FAILED'
                                 THEN sync_attempts + 1 ELSE sync_attempts END,
            status = COALESCE(:status, status)
        WHERE id = :id
        """
    )
    suspend fun updateSyncState(
        id: String,
        state: String,
        serverId: String?,
        error: String?,
        nextAttemptAt: Long?,
        status: String?,
    )

    /**
     * Aplica el estado que reporta el servidor. Se busca también por `server_id`
     * por si el servidor identifica la solicitud con su propio id.
     *
     * `resolution_read` vuelve a 0 solo si la resolución es nueva o cambió. SQLite
     * evalúa cada expresión del SET contra la fila ANTERIOR, así que la
     * comparación ve la resolución que había antes de esta escritura. Sin esto,
     * cada sincronización volvería a marcar como no leída una respuesta que el
     * productor ya vio.
     */
    @Query(
        """
        UPDATE review_requests SET
            resolution_read = CASE
                WHEN :resolutionType IS NOT NULL AND (
                    resolution_type IS NOT :resolutionType
                    OR resolved_pest_id IS NOT :resolvedPestId
                    OR agronomist_response IS NOT :agronomistResponse
                    OR resolved_at IS NOT :resolvedAt)
                THEN 0 ELSE resolution_read END,
            status = :status,
            resolution_type = :resolutionType,
            resolved_pest_id = :resolvedPestId,
            agronomist_response = :agronomistResponse,
            resolved_at = :resolvedAt
        WHERE id = :id OR server_id = :id
        """
    )
    suspend fun applyRemoteStatus(
        id: String,
        status: String,
        resolutionType: String?,
        resolvedPestId: String?,
        agronomistResponse: String?,
        resolvedAt: Long?,
    ): Int

    @Query("UPDATE review_requests SET resolution_read = 1 WHERE id = :id")
    suspend fun markResolutionRead(id: String)

    @Query("SELECT COUNT(*) FROM review_requests WHERE sync_state = 'PENDING'")
    fun observePendingCount(): Flow<Int>
}
