package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.SemanticVersion
import co.agrovision.core.common.flatMap
import co.agrovision.core.common.map
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.mlmodel.ModelUpdateDecision
import co.agrovision.core.model.port.AppVersionProvider
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.TelemetryRecorder
import javax.inject.Inject

/**
 * Consulta el manifiesto y decide si procede actualizar.
 *
 * El servidor resuelve qué versión corresponde a este dispositivo concreto; el
 * cliente nunca decide, solo obedece (§8.2). Esto concentra la lógica de
 * despliegue gradual en un lugar auditable en vez de repartirla por una flota
 * de teléfonos que no se pueden inspeccionar.
 */
class CheckModelUpdateUseCase @Inject constructor(
    private val registry: ModelRegistryRepository,
    private val appVersion: AppVersionProvider,
    private val clock: Clock,
) {

    suspend operator fun invoke(force: Boolean = false): AgroResult<ModelUpdateDecision, DomainError> {
        val state = registry.currentState()
        val now = clock.nowEpochMillis()

        // No más de una comprobación cada 24 h (§8.3). Más frecuente gasta datos
        // prepago sin aportar frescura útil.
        val elapsed = now - state.lastCheckEpochMillis
        if (!force && elapsed < AgroConstants.Model.UPDATE_CHECK_INTERVAL.inWholeMilliseconds) {
            return AgroResult.success(ModelUpdateDecision.UpToDate)
        }

        return registry.fetchManifest().map { manifest ->
            registry.recordCheck(now)
            // Sin modelo publicado la app sigue con el que trae de fábrica
            if (manifest == null) ModelUpdateDecision.UpToDate else decide(manifest, state.active?.version)
        }
    }

    private fun decide(manifest: ModelManifest, activeVersion: String?): ModelUpdateDecision {
        // El interruptor de emergencia se atiende antes que nada: su razón de
        // ser es retirar una versión sin esperar al ciclo normal (§8.4).
        // Solo afecta a quien lo tiene instalado: un teléfono que nunca recibió esa
        // versión no tiene nada que revertir, y revertir su modelo actual sería un daño.
        if (manifest.killSwitch) {
            return if (activeVersion == manifest.version) {
                ModelUpdateDecision.KillSwitchTriggered(manifest.version)
            } else {
                ModelUpdateDecision.UpToDate
            }
        }

        val installed = SemanticVersion.parseOrNull(manifest.minAppVersion)
        val running = SemanticVersion.parseOrNull(appVersion.versionName())
        if (installed != null && running != null && running < installed) {
            return ModelUpdateDecision.RequiresAppUpdate(manifest.minAppVersion)
        }

        val offered = SemanticVersion.parseOrNull(manifest.version) ?: return ModelUpdateDecision.UpToDate
        val current = activeVersion?.let(SemanticVersion::parseOrNull) ?: SemanticVersion.ZERO

        return if (offered > current) ModelUpdateDecision.Available(manifest)
        else ModelUpdateDecision.UpToDate
    }
}

/**
 * Descarga, verifica, escribe en la ranura inactiva y conmuta el puntero.
 *
 * El orden de los pasos es el contrato, y cada uno existe por un fallo concreto:
 *
 *  1. Descarga a temporal.
 *  2. **SHA-256 calculado en flujo durante la descarga.** Discrepancia →
 *     descarte inmediato. Detecta corrupción de transferencia sobre 3G
 *     inestable, que es la condición normal de la red objetivo.
 *  3. **Firma Ed25519 contra la clave pública embebida.** Fallo → descarte y
 *     evento de seguridad. Sin esto, cualquiera con control del CDN ejecuta
 *     código de modelo arbitrario en la flota.
 *  4. **Escritura en la ranura INACTIVA.** La activa permanece intacta y en uso.
 *  5. **Inferencia de humo contra una imagen de referencia embebida.** Detecta
 *     modelos corruptos o incompatibles antes de exponerlos al productor.
 *  6. **Conmutación atómica del puntero.** Único punto de no retorno.
 *
 * Nunca se sobrescribe el modelo en uso: LiteRT lo mantiene mapeado en memoria y
 * hacerlo produce un fallo de segmentación nativo sin traza aprovechable
 * (§23, error nº 11).
 */
class ActivateModelUseCase @Inject constructor(
    private val registry: ModelRegistryRepository,
    private val telemetry: TelemetryRecorder,
) {

    suspend operator fun invoke(manifest: ModelManifest): AgroResult<Unit, DomainError> {
        val previous = registry.currentState().active?.version

        return registry.installToStandby(manifest)
            .flatMap { installed -> registry.activate(installed.slot) }
            .also { result ->
                telemetry.recordModelUpdate(
                    fromVersion = previous,
                    toVersion = manifest.version,
                    success = result.isSuccess,
                )
            }
    }
}

/**
 * Reversión. No requiere descarga porque el modelo previo sigue en disco: esa es
 * exactamente la razón de ser del esquema de dos ranuras, y lo que permite
 * cumplir el objetivo de menos de 30 minutos entre detección de regresión y
 * reversión efectiva (§3.2 RNF).
 */
class RollBackModelUseCase @Inject constructor(
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(): AgroResult<Unit, DomainError> = registry.rollBack()
}
