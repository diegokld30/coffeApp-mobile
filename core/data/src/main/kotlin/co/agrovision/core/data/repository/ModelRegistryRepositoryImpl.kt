package co.agrovision.core.data.repository

import android.util.Log
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.datastore.DeviceIdentityStore
import co.agrovision.core.datastore.ModelSlotStore
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.InstalledModel
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.mlmodel.ModelRegistryState
import co.agrovision.core.model.mlmodel.ModelSlot
import co.agrovision.core.model.mlmodel.ModelUpdateFailure
import co.agrovision.core.model.port.ActiveModelProvider
import co.agrovision.core.model.port.AppVersionProvider
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.LoadedModel
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.SyncGateway
import co.agrovision.core.model.port.ModelSource
import co.agrovision.ml.registry.ArtifactInstaller
import co.agrovision.ml.registry.ModelSlotLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registro local de modelos: ranuras A/B, negociación y activación.
 *
 * Compone tres piezas que a propósito no se conocen entre sí:
 *
 *  · [ModelSlotStore] — el puntero atómico. Único punto de no retorno (§8.3).
 *  · [ArtifactInstaller] — descarga, hash, firma y prueba de humo.
 *  · [SyncGateway] — el manifiesto, **sin JWT** (§6.2). Un usuario anónimo tiene
 *    que poder recibir actualizaciones de modelo; exigir autenticación aquí lo
 *    congelaría en la versión de fábrica para siempre (§23, error nº 5).
 *
 * Nótese que la tercera entra por el puerto del dominio y no por el servicio
 * HTTP: así este módulo no ve tipos de Retrofit, y nada invita a que un
 * repositorio empiece a hacer peticiones por su cuenta.
 */
@Singleton
class ModelRegistryRepositoryImpl @Inject constructor(
    private val slots: ModelSlotStore,
    private val layout: ModelSlotLayout,
    private val installer: ArtifactInstaller,
    private val gateway: SyncGateway,
    private val identity: DeviceIdentityStore,
    private val appVersion: AppVersionProvider,
    private val clock: Clock,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : ModelRegistryRepository, ActiveModelProvider {

    override fun observeState(): Flow<ModelRegistryState> = slots.pointer.map { pointer ->
        buildState(pointer.activeSlot, pointer.lastCheckEpochMillis)
    }

    override suspend fun currentState(): ModelRegistryState = withContext(io) {
        val pointer = slots.current()
        buildState(pointer.activeSlot, pointer.lastCheckEpochMillis)
    }

    override suspend fun fetchManifest(): AgroResult<ModelManifest?, DomainError> = withContext(io) {
        gateway.fetchModelManifest(
            appVersion = appVersion.versionName(),
            deviceId = identity.deviceId(),
        )
    }

    override suspend fun installToStandby(
        manifest: ModelManifest,
    ): AgroResult<InstalledModel, DomainError> = withContext(io) {
        val pointer = slots.current()

        val result = installer.install(
            manifest = manifest,
            // SIEMPRE la ranura inactiva. LiteRT mantiene la activa mapeada en
            // memoria y sobrescribirla produce un fallo de segmentación nativo
            // sin traza (§23, error nº 11).
            targetSlot = pointer.standbySlot,
            activeSlot = pointer.activeSlot,
        )

        when (result) {
            is AgroResult.Success -> result
            is AgroResult.Failure -> {
                slots.recordFailure()
                AgroResult.failure(DomainError.ModelUpdateRejected(result.error))
            }
        }
    }

    override suspend fun activate(slot: ModelSlot): AgroResult<Unit, DomainError> = withContext(io) {
        val installed = installer.readInstalled(slot)
            ?: return@withContext AgroResult.failure(
                DomainError.ModelUpdateRejected(ModelUpdateFailure.SMOKE_TEST_FAILED)
            )

        // Punto de no retorno. A partir de aquí, la siguiente instanciación del
        // intérprete usa el modelo nuevo; los intérpretes vivos siguen con el
        // anterior hasta liberarse, lo que evita invalidar buffers mapeados en
        // uso (§8.3).
        slots.activate(slot, installed.version, clock.nowEpochMillis())
        AgroResult.unit
    }

    override suspend fun rollBack(): AgroResult<Unit, DomainError> = withContext(io) {
        // Sin descarga y sin red: el modelo previo sigue en disco. Esa es la
        // razón de ser del esquema de dos ranuras y lo que hace alcanzable el
        // objetivo de menos de 30 minutos hasta la reversión efectiva.
        if (slots.rollBack()) {
            AgroResult.unit
        } else {
            Log.w(TAG, "No hay ranura anterior a la que revertir.")
            AgroResult.failure(DomainError.ModelUnavailable)
        }
    }

    override suspend fun activeCalibration(): CalibrationParams? = withContext(io) {
        installer.readInstalled(slots.current().activeSlot)?.calibration
    }

    override suspend fun recordCheck(nowEpochMillis: Long) = slots.recordCheck(nowEpochMillis)

    // ── ActiveModelProvider ──────────────────────────────────────────────────

    /**
     * Entrega el modelo activo al subsistema de inferencia.
     *
     * Se mapea a fichero, no a memoria: reduce la presión sobre el montículo, que
     * en un dispositivo de 2 GB es la diferencia entre funcionar y morir por
     * memoria (§7.3).
     *
     * ⚠️ El cifrado en reposo del modelo (§15.4) exigiría `ModelSource.InMemory`
     * con la clave del Android Keystore. Está pendiente; ver README.
     */
    override suspend fun activeModel(): LoadedModel? = withContext(io) {
        val slot = slots.current().activeSlot
        val installed = installer.readInstalled(slot) ?: return@withContext null

        LoadedModel(
            version = installed.version,
            labels = installed.labels,
            calibration = installed.calibration,
            source = ModelSource.MappedFile(layout.modelFile(slot)),
            embeddingDim = installed.embeddingDim,
        )
    }

    private suspend fun buildState(active: ModelSlot, lastCheck: Long) = ModelRegistryState(
        activeSlot = active,
        active = installer.readInstalled(active),
        standby = installer.readInstalled(active.other),
        lastCheckEpochMillis = lastCheck,
        lastFailure = null,
    )

    private companion object {
        const val TAG = "AgroVision.Registry"
    }
}
