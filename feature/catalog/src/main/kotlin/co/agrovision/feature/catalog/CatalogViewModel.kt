package co.agrovision.feature.catalog

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.pest.PestKind
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.usecase.GetPestDetailUseCase
import co.agrovision.core.model.usecase.SearchCatalogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class CatalogUiState(
    val query: String = "",
    val results: List<PestTaxon> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    /** Filtro por tipo; `null` es «Todas». */
    val kind: PestKind? = null,
    /** Tipos presentes en el catálogo, en el orden en que se ofrecen como
     *  filtro. Solo se ofrecen los que existen: un filtro que siempre da
     *  cero resultados es un callejón sin salida. */
    val availableKinds: List<PestKind> = emptyList(),
) {
    val isEmptySearch: Boolean get() = !loading && results.isEmpty() && query.isNotBlank()
    val isEmptyCatalog: Boolean get() = !loading && total == 0
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val searchCatalog: SearchCatalogUseCase,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val kind = MutableStateFlow<PestKind?>(null)

    val state: StateFlow<CatalogUiState> = combine(
        // `debounce` sobre el texto y no sobre el resultado: en un teléfono de
        // gama baja, filtrar en cada pulsación de tecla produce saltos visibles
        // al escribir, que es justo cuando el usuario está mirando.
        query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
        searchCatalog(""),
        kind,
    ) { text, all, selected ->
        CatalogUiState(
            query = text,
            results = all.filter { it.matches(text) && (selected == null || it.kind == selected) },
            total = all.size,
            loading = false,
            kind = selected,
            availableKinds = FILTER_ORDER.filter { k -> all.any { it.kind == k } },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CatalogUiState(),
    )

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun onKindChange(selected: PestKind?) {
        kind.value = selected
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
        const val STOP_TIMEOUT_MS = 5_000L

        /** Primero lo que exige actuar ya (enfermedad, plaga), después lo que
         *  se corrige con manejo (deficiencia). */
        val FILTER_ORDER = listOf(
            PestKind.ENFERMEDAD,
            PestKind.PLAGA,
            PestKind.DEFICIENCIA,
            PestKind.OTRO,
            PestKind.SANO,
        )
    }
}

/** Detalle de una ficha. Se separa del listado porque su ciclo de vida es
 *  distinto: entra y sale con la navegación, no vive mientras la lista existe. */
@HiltViewModel
class PestDetailViewModel @Inject constructor(
    private val getPestDetail: GetPestDetailUseCase,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val pestId: String = savedStateHandle.get<String>(ARG_PEST_ID).orEmpty()

    val pest: StateFlow<PestTaxon?> = getPestDetail(pestId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = null,
    )

    companion object {
        const val ARG_PEST_ID = "pestId"
    }
}
