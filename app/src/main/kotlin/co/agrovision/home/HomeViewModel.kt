package co.agrovision.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.pest.PestKind
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.usecase.SearchCatalogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class HomeUiState(
    val pendingCount: Int = 0,
    val catalogCount: Int = 0,
    /** Fichas para el carrusel de Inicio. Sin las de planta sana: el
     *  carrusel es para reconocer problemas, no para mostrar hojas bonitas. */
    val featured: List<PestTaxon> = emptyList(),
)

/**
 * Estado de la pantalla de inicio.
 *
 * Las dos cifras que muestra —fichas disponibles y capturas por enviar— salen de
 * Room, no de la red. Sirven para que las tarjetas digan algo concreto en vez de
 * un rótulo genérico: «12 por enviar» le dice al productor que su trabajo está
 * guardado y esperando, que es exactamente la tranquilidad que necesita cuando
 * lleva días sin señal.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    captures: CaptureRepository,
    searchCatalog: SearchCatalogUseCase,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        captures.observePendingCount(),
        searchCatalog(""),
    ) { pending, catalog ->
        HomeUiState(
            pendingCount = pending,
            catalogCount = catalog.size,
            featured = catalog
                .filter { it.kind != PestKind.SANO && it.imageRefs.isNotEmpty() }
                .take(FEATURED_MAX),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(),
    )

    private companion object {
        const val FEATURED_MAX = 8
    }
}
