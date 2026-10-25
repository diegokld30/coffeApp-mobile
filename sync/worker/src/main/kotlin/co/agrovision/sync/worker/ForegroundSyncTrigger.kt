package co.agrovision.sync.worker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.mlmodel.ModelUpdateDecision
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.port.ConnectivityMonitor
import co.agrovision.core.model.usecase.ActivateModelUseCase
import co.agrovision.core.model.usecase.RollBackModelUseCase
import co.agrovision.core.model.usecase.CheckModelUpdateUseCase
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.core.model.usecase.SyncTrigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disparador **primario** de la sincronización (§9.4, ADR-007).
 *
 * ## Por qué este es el primario
 *
 * Es la única ruta que **siempre** funciona, independientemente del fabricante.
 * Las capas de personalización de Xiaomi, Tecno o Infinix pueden suprimir
 * WorkManager indefinidamente, pero no pueden impedir que la app haga trabajo
 * mientras está en primer plano.
 *
 * Y encaja con el comportamiento real del productor: abre la aplicación cada vez
 * que escanea, lo que garantiza ventanas de sincronización frecuentes sin pedirle
 * nada. La sincronización más fiable es la que ocurre cuando el usuario ya está
 * usando la app.
 *
 * Se engancha a `ProcessLifecycleOwner`, no a una `Activity`: cubre tanto el
 * arranque en frío como la vuelta desde segundo plano, y no se dispara en cada
 * rotación de pantalla.
 */
@Singleton
class ForegroundSyncTrigger @Inject constructor(
    private val syncPending: SyncPendingUseCase,
    private val scheduler: SyncScheduler,
    private val settings: SettingsRepository,
    private val registry: ModelRegistryRepository,
    private val checkModelUpdate: CheckModelUpdateUseCase,
    private val activateModel: ActivateModelUseCase,
    private val rollBackModel: RollBackModelUseCase,
    private val connectivity: ConnectivityMonitor,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    // Scope propio del proceso, no del ViewModel: la sincronización tiene que
    // sobrevivir a que el productor navegue entre pantallas. `SupervisorJob` para
    // que un fallo no cancele las sincronizaciones siguientes.
    private val scope = CoroutineScope(SupervisorJob() + io)

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Pone el modelo al día cada vez que la app pasa a primer plano.
     *
     * Sin modelo se pide siempre y en cualquier red: sin él la app no puede escanear. Con uno
     * instalado se respeta la política de datos del productor (con «solo WiFi» espera a la
     * WiFi, donde también corre el trabajo en segundo plano) y el caso de uso limita la
     * frecuencia a una consulta cada 15 minutos.
     */
    private suspend fun updateModel() {
        val hasModel = registry.currentState().active != null
        if (hasModel) {
            val network = connectivity.current()
            if (!network.isOnline) return
            if (network.isMetered && !settings.current().syncNetworkPolicy.allowsMetered) return
        }

        val decision = (checkModelUpdate(force = !hasModel) as? AgroResult.Success)?.value
        when (decision) {
            is ModelUpdateDecision.Available -> activateModel(decision.manifest)
            // Retirada de emergencia: el modelo anterior sigue en disco, no hay nada que descargar
            is ModelUpdateDecision.KillSwitchTriggered -> rollBackModel()
            else -> Unit
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            // No se comprueba conectividad aquí: el caso de uso ya lo hace y
            // devuelve `NoConnection` sin efectos. Duplicar la comprobación
            // abriría la puerta a que las dos discrepen.
            // El modelo se actualiza ya, sin esperar los minutos de retraso del trabajo en
            // segundo plano: una versión nueva llega en cuanto el productor abre la app.
            updateModel()

            syncPending(SyncTrigger.FOREGROUND)

            // Se encola además el trabajo en segundo plano. Si el fabricante lo
            // ejecuta, mejora la frescura; si lo suprime, no se pierde nada
            // porque acabamos de sincronizar en primer plano.
            val preferences = settings.current()
            scheduler.scheduleOpportunistic(preferences.syncNetworkPolicy)
            scheduler.scheduleModelCheck(preferences.syncNetworkPolicy)
        }
    }
}
