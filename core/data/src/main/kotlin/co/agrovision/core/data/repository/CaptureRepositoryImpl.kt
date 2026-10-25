package co.agrovision.core.data.repository

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.data.mapper.toDomain
import co.agrovision.core.data.mapper.toEntity
import co.agrovision.core.database.dao.CaptureDao
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.ImageStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaptureRepositoryImpl @Inject constructor(
    private val dao: CaptureDao,
    private val images: ImageStore,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : CaptureRepository {

    override fun observeHistory(): Flow<List<Capture>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Capture?> =
        dao.observeById(id).map { it?.toDomain() }

    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    override fun observePendingBytes(): Flow<Long> = dao.observePendingBytes()

    override suspend fun save(capture: Capture): AgroResult<Unit, DomainError> = withContext(io) {
        try {
            // El tamaño se calcula aquí y no en el dominio: es un detalle de
            // almacenamiento, y el dominio no debería saber que las imágenes son
            // archivos.
            val bytes = File(capture.imagePath).length() + File(capture.thumbnailPath).length()
            dao.insert(capture.toEntity(sizeBytes = bytes))
            AgroResult.unit
        } catch (error: android.database.sqlite.SQLiteConstraintException) {
            // Clave primaria duplicada. Con UUIDv7 no debería ocurrir salvo que
            // alguien reintente guardar la misma captura, y en ese caso lo
            // correcto es no duplicarla ni fallar.
            AgroResult.unit
        } catch (error: Exception) {
            AgroResult.failure(DomainError.PersistenceFailed(error.message.orEmpty()))
        }
    }

    override suspend fun findById(id: String): Capture? = withContext(io) {
        dao.findById(id)?.toDomain()
    }

    override suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<Capture> =
        withContext(io) {
            dao.nextBatchToUpload(limit, nowEpochMillis).map { it.toDomain() }
        }

    override suspend fun updateSyncState(
        id: String,
        state: SyncState,
        serverId: String?,
        error: String?,
        nextAttemptAtEpochMillis: Long?,
        countsAsAttempt: Boolean,
    ) = withContext(io) {
        dao.updateSyncState(id, state.name, serverId, error, nextAttemptAtEpochMillis, countsAsAttempt)
    }

    override suspend fun requeueStuckUploads() {
        withContext(io) { dao.requeueStuckUploads() }
    }

    override suspend fun skipUnsent() {
        withContext(io) { dao.skipUnsent() }
    }

    override suspend fun confirmPrediction(id: String): AgroResult<Unit, DomainError> =
        withContext(io) {
            if (dao.confirm(id) > 0) AgroResult.unit
            else AgroResult.failure(DomainError.NotFound("Capture", id))
        }

    override suspend fun correctPrediction(
        id: String,
        classId: String,
    ): AgroResult<Unit, DomainError> = withContext(io) {
        if (dao.correct(id, classId) > 0) AgroResult.unit
        else AgroResult.failure(DomainError.NotFound("Capture", id))
    }

    /**
     * Purga a 90 días de capturas ya subidas y confirmadas (§9.3).
     *
     * **El orden importa.** `dao.purge` es transaccional: devuelve las filas y las
     * borra en la misma transacción. Solo después se borran los archivos. Al
     * revés —archivos primero— un fallo entre los dos pasos dejaría filas
     * apuntando a imágenes que ya no existen, y el historial mostraría huecos que
     * ninguna sincronización posterior puede arreglar.
     *
     * En el orden correcto, el peor caso es un archivo huérfano en disco: molesto,
     * recuperable en la siguiente purga, y nunca visible para el productor.
     */
    override suspend fun purgeExpired(nowEpochMillis: Long): Int = withContext(io) {
        val cutoff = nowEpochMillis - AgroConstants.Image.RETENTION.inWholeMilliseconds
        val purged = dao.purge(cutoff)
        if (purged.isNotEmpty()) {
            images.delete(purged.flatMap { listOf(it.imagePath, it.thumbnailPath) })
        }
        purged.size
    }
}
