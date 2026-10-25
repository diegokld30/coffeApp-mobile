package co.agrovision.core.model.usecase

import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.identity.UploadGate
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.PestCatalogRepository
import co.agrovision.core.model.scan.GateOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Estado de la compuerta de subida, para mostrarlo con el número real de
 * elementos en cola.
 *
 * El mensaje importa tanto como el mecanismo: «tienes 12 capturas esperando a
 * que validemos tu cuenta» le dice al productor que su trabajo está a salvo.
 * «No autorizado» le dice que lo perdió.
 */
class ObserveUploadGateUseCase @Inject constructor(
    private val account: AccountRepository,
    private val captures: CaptureRepository,
) {
    operator fun invoke(): Flow<UploadGate> =
        combine(account.observe(), captures.observePendingCount()) { current, queued ->
            UploadGate.evaluate(
                tier = current.tier,
                queuedCount = queued,
                registeredAtEpochMillis = current.registeredAtEpochMillis,
                sessionExpired = current.sessionExpired,
            )
        }
}

/** Historial local con filtros (§3.1 RF-15). Funciona sin conexión, como todo
 *  lo demás. */
class ObserveScanHistoryUseCase @Inject constructor(
    private val captures: CaptureRepository,
) {
    operator fun invoke(filter: HistoryFilter): Flow<List<Capture>> =
        captures.observeHistory().map { all -> all.filter(filter::matches) }
}

data class HistoryFilter(
    val fromEpochMillis: Long? = null,
    val toEpochMillis: Long? = null,
    val cropType: CropType? = null,
    val outcome: GateOutcome? = null,
) {
    fun matches(capture: Capture): Boolean =
        (fromEpochMillis == null || capture.capturedAtEpochMillis >= fromEpochMillis) &&
            (toEpochMillis == null || capture.capturedAtEpochMillis <= toEpochMillis) &&
            (cropType == null || capture.cropType == cropType) &&
            (outcome == null || capture.gateOutcome == outcome)

    companion object {
        val NONE = HistoryFilter()
    }
}

class SearchCatalogUseCase @Inject constructor(
    private val catalog: PestCatalogRepository,
) {
    /**
     * Busca por nombre común, científico **y sinónimos regionales**. El último
     * no es un extra: en el Macizo una misma plaga tiene tres o cuatro nombres
     * según la vereda, y el productor teclea el que conoce (§2.1).
     */
    operator fun invoke(query: String): Flow<List<PestTaxon>> =
        catalog.observeAll().map { all -> all.filter { it.matches(query) } }
}

class GetPestDetailUseCase @Inject constructor(
    private val catalog: PestCatalogRepository,
) {
    operator fun invoke(id: String): Flow<PestTaxon?> = catalog.observeById(id)
}

/**
 * Purga de capturas ya sincronizadas y confirmadas a los 90 días.
 *
 * Un teléfono de 32 GB con el almacenamiento casi lleno es el caso común, no el
 * excepcional (§9.3). La copia autoritativa vive en el servidor; lo que se borra
 * aquí no se pierde.
 */
class PurgeExpiredCapturesUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int = captures.purgeExpired(clock.nowEpochMillis())
}
