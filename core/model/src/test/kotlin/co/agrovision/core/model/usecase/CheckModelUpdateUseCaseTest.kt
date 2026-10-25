package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.InstalledModel
import co.agrovision.core.model.mlmodel.ModelChannel
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.mlmodel.ModelRegistryState
import co.agrovision.core.model.mlmodel.ModelSlot
import co.agrovision.core.model.mlmodel.ModelUpdateDecision
import co.agrovision.core.model.port.AppVersionProvider
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ModelRegistryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Qué hace la app con lo que responde el manifiesto. Los dos casos que protegen:
 *  - Sin modelo publicado (204) no hay error: se queda con el de fábrica.
 *  - El kill-switch de una versión que este teléfono nunca instaló no le quita
 *    el modelo que sí tiene.
 */
class CheckModelUpdateUseCaseTest {

    private fun manifest(version: String, killSwitch: Boolean = false) = ModelManifest(
        version = version,
        channel = ModelChannel.PRODUCTION,
        minAppVersion = "1.0.0",
        sizeBytes = 1_000L,
        sha256 = "ab".repeat(32),
        signatureBase64 = "firma",
        modelUrl = "m",
        labelsUrl = "l",
        calibrationUrl = "c",
        releaseNotes = "",
        killSwitch = killSwitch,
    )

    private fun installed(version: String) = InstalledModel(
        slot = ModelSlot.A,
        version = version,
        channel = ModelChannel.PRODUCTION,
        sha256 = "ab".repeat(32),
        classCount = 1,
        embeddingDim = 1,
        labels = emptyList(),
        calibration = CalibrationParams(
            temperature = 1f,
            confidenceThreshold = 0.7f,
            marginThreshold = 0.1f,
            oodThreshold = 2f,
            energyWeight = 0.5f,
            classCentroids = listOf(floatArrayOf(0f)),
            precisionMatrix = floatArrayOf(1f),
            energyMean = 0f,
            energyStdDev = 1f,
        ),
        installedAtEpochMillis = 0L,
    )

    private fun stateWith(activeVersion: String?) = ModelRegistryState(
        activeSlot = ModelSlot.A,
        active = activeVersion?.let(::installed),
        standby = null,
        lastCheckEpochMillis = 0L,
        lastFailure = null,
    )

    /** A mano porque MockK no sabe devolver `AgroResult` genérico. */
    private class FakeRegistry(
        private val state: ModelRegistryState,
        private val fetched: AgroResult<ModelManifest?, DomainError>,
    ) : ModelRegistryRepository {
        var checks = 0

        override fun observeState(): Flow<ModelRegistryState> = flowOf(state)
        override suspend fun currentState() = state
        override suspend fun fetchManifest() = fetched
        override suspend fun installToStandby(manifest: ModelManifest): AgroResult<InstalledModel, DomainError> =
            error("no se usa")
        override suspend fun activate(slot: ModelSlot): AgroResult<Unit, DomainError> = error("no se usa")
        override suspend fun rollBack(): AgroResult<Unit, DomainError> = error("no se usa")
        override suspend fun activeCalibration(): CalibrationParams? = null
        override suspend fun recordCheck(nowEpochMillis: Long) {
            checks++
        }
    }

    private fun useCase(registry: FakeRegistry) = CheckModelUpdateUseCase(
        registry = registry,
        appVersion = AppVersionProvider { "2.0.0" },
        clock = Clock { 10_000_000_000L },
    )

    @Test
    fun `sin modelo publicado la app sigue como estaba`() = runTest {
        val registry = FakeRegistry(stateWith("1.0.0"), AgroResult.success(null))

        val result = useCase(registry)()

        assertEquals(ModelUpdateDecision.UpToDate, (result as AgroResult.Success).value)
        assertEquals(1, registry.checks)
    }

    @Test
    fun `el kill-switch de la version instalada la retira`() = runTest {
        val result = useCase(FakeRegistry(stateWith("1.2.0"), AgroResult.success(manifest("1.2.0", killSwitch = true))))()

        assertEquals(ModelUpdateDecision.KillSwitchTriggered("1.2.0"), (result as AgroResult.Success).value)
    }

    @Test
    fun `el kill-switch de otra version no toca el modelo instalado`() = runTest {
        val result = useCase(FakeRegistry(stateWith("1.0.0"), AgroResult.success(manifest("1.2.0", killSwitch = true))))()

        assertEquals(ModelUpdateDecision.UpToDate, (result as AgroResult.Success).value)
    }

    @Test
    fun `una version mayor se ofrece como disponible`() = runTest {
        val offered = manifest("1.1.0")
        val result = useCase(FakeRegistry(stateWith("1.0.0"), AgroResult.success(offered)))()

        assertEquals(ModelUpdateDecision.Available(offered), (result as AgroResult.Success).value)
    }
}
