package co.agrovision.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import co.agrovision.core.database.entity.CaptureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    @Query("SELECT * FROM captures ORDER BY captured_at DESC")
    fun observeAll(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE id = :id")
    fun observeById(id: String): Flow<CaptureEntity?>

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun findById(id: String): CaptureEntity?

    @Query("SELECT COUNT(*) FROM captures WHERE sync_state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** `COALESCE` porque `SUM` sobre cero filas devuelve NULL, no 0, y eso
     *  aparecería en la interfaz como «-- MB pendientes». */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM captures WHERE sync_state = 'PENDING'")
    fun observePendingBytes(): Flow<Long>

    /**
     * Siguiente lote a subir.
     *
     * El ORDER BY implementa la prioridad de cola (§9.4) **dentro de SQL**, no
     * con un `sortedBy` sobre una lista que en zona sin señal puede tener miles
     * de filas:
     *   1. Correcciones del productor — dato de entrenamiento premium.
     *   2. Capturas confirmadas.
     *   3. El resto, por antigüedad.
     *
     * `next_attempt_at` respeta el backoff exponencial: una fila que falló hace
     * treinta segundos no se vuelve a intentar en el ciclo siguiente.
     */
    @Query(
        """
        SELECT * FROM captures
        WHERE sync_state = 'PENDING'
          AND (next_attempt_at IS NULL OR next_attempt_at <= :nowEpochMillis)
        ORDER BY
          CASE WHEN user_correction_class_id IS NOT NULL THEN 0
               WHEN user_confirmed = 1 THEN 1
               ELSE 2 END ASC,
          captured_at ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<CaptureEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(capture: CaptureEntity)

    @Query(
        """
        UPDATE captures
        SET sync_state = :state,
            server_id = COALESCE(:serverId, server_id),
            last_sync_error = :error,
            next_attempt_at = :nextAttemptAt,
            sync_attempts = CASE WHEN :countsAsAttempt AND (:state = 'PENDING' OR :state = 'FAILED')
                                 THEN sync_attempts + 1 ELSE sync_attempts END
        WHERE id = :id
        """
    )
    suspend fun updateSyncState(
        id: String,
        state: String,
        serverId: String?,
        error: String?,
        nextAttemptAt: Long?,
        countsAsAttempt: Boolean = true,
    )

    /**
     * Confirmar también reencola la fila si ya estaba subida: el servidor tiene la
     * captura pero no la confirmación. Al reenviarla llega como duplicada y el
     * servidor actualiza la confirmación de la que ya guardó.
     */
    @Query(
        """
        UPDATE captures
        SET user_confirmed = 1,
            sync_state = CASE WHEN sync_state = 'UPLOADED' THEN 'PENDING' ELSE sync_state END,
            next_attempt_at = CASE WHEN sync_state = 'UPLOADED' THEN NULL ELSE next_attempt_at END
        WHERE id = :id
        """
    )
    suspend fun confirm(id: String): Int

    @Query("UPDATE captures SET sync_state = 'PENDING' WHERE sync_state = 'UPLOADING'")
    suspend fun requeueStuckUploads(): Int

    @Query("UPDATE captures SET sync_state = 'SKIPPED' WHERE sync_state IN ('PENDING', 'UPLOADING', 'FAILED')")
    suspend fun skipUnsent(): Int

    /**
     * Corregir reencola la fila aunque ya estuviera subida: el servidor tiene
     * la captura pero no la corrección, y la corrección es justamente el dato
     * valioso.
     */
    @Query(
        """
        UPDATE captures
        SET user_correction_class_id = :classId,
            user_confirmed = 0,
            sync_state = CASE WHEN sync_state = 'SKIPPED' THEN 'SKIPPED' ELSE 'PENDING' END,
            next_attempt_at = NULL
        WHERE id = :id
        """
    )
    suspend fun correct(id: String, classId: String): Int

    @Query(
        """
        SELECT * FROM captures
        WHERE sync_state = 'UPLOADED'
          AND (user_confirmed = 1 OR user_correction_class_id IS NOT NULL)
          AND captured_at < :cutoffEpochMillis
        """
    )
    suspend fun findPurgeable(cutoffEpochMillis: Long): List<CaptureEntity>

    @Query("DELETE FROM captures WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /** Purga transaccional: se borran las filas y quien llama borra los archivos.
     *  Devolver las rutas antes de borrar evita quedarse con huérfanos en disco. */
    @Transaction
    suspend fun purge(cutoffEpochMillis: Long): List<CaptureEntity> {
        val purgeable = findPurgeable(cutoffEpochMillis)
        if (purgeable.isNotEmpty()) deleteByIds(purgeable.map { it.id })
        return purgeable
    }
}
