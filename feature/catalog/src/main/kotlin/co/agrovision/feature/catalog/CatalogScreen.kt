package co.agrovision.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.component.SeverityStrip
import co.agrovision.core.designsystem.component.SeverityStripDensity
import co.agrovision.core.designsystem.component.StatusPill
import co.agrovision.core.designsystem.component.StatusTone
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.pest.PestKind
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.pest.SeverityLevel

/**
 * Catálogo de plagas y problemas del cultivo.
 *
 * Es la pantalla que el productor consulta **en el lote, sin señal**, así que
 * todo lo que muestra sale de Room. Nada aquí depende de la red ni del modelo.
 *
 * La búsqueda incluye los sinónimos regionales (§2.1): en el Macizo una misma
 * plaga tiene tres o cuatro nombres según la vereda, y el productor teclea el
 * que conoce, no el binomio latino.
 *
 * ## Por qué cuadrícula de fotos
 *
 * El productor reconoce la plaga por cómo se ve, no por cómo se llama. Con la
 * foto grande compara lo que tiene en la mano con la ficha antes de leer una
 * sola palabra.
 */
@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onQueryChange: (String) -> Unit,
    onKindChange: (PestKind?) -> Unit,
    onPestClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = AgroTheme.dimensions.gutter

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = AgroTheme.dimensions.gutterWide),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "cabecera") {
            Column(
                modifier = Modifier.padding(top = AgroTheme.dimensions.gutterWide),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.catalogo_titulo),
                    style = MaterialTheme.typography.displaySmall,
                )
                if (state.total > 0) {
                    Text(
                        text = stringResource(R.string.catalogo_cuenta, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AgroTheme.semantic.mutedText,
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "busqueda") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.availableKinds.size > 1) {
                    KindFilters(
                        available = state.availableKinds,
                        selected = state.kind,
                        onSelect = onKindChange,
                    )
                }
            }
        }

        when {
            state.isEmptyCatalog -> item(span = { GridItemSpan(maxLineSpan) }, key = "vacio") {
                EmptyState(
                    title = stringResource(R.string.catalogo_vacio_titulo),
                    body = stringResource(R.string.catalogo_vacio_cuerpo),
                    icon = Icons.Filled.Search,
                )
            }

            state.isEmptySearch -> item(span = { GridItemSpan(maxLineSpan) }, key = "sin-resultados") {
                EmptyState(
                    title = stringResource(R.string.catalogo_sin_resultados_titulo, state.query),
                    body = stringResource(R.string.catalogo_sin_resultados_cuerpo),
                    icon = Icons.Filled.Search,
                )
            }

            else -> items(state.results, key = { it.id }) { pest ->
                PestTile(pest = pest, onClick = { onPestClick(pest.id) })
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = CircleShape,
        // Campo sin contorno: se distingue del fondo por claridad, y el
        // contorno solo aparece al escribir, que es cuando importa.
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = AgroTheme.semantic.card,
            focusedContainerColor = AgroTheme.semantic.card,
            unfocusedBorderColor = AgroTheme.semantic.card,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = AgroTheme.semantic.mutedText,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        placeholder = { Text(stringResource(R.string.catalogo_buscar)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Borrar búsqueda")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * Filtro por tipo de problema. Píldoras grandes, de una sola fila desplazable:
 * con el pulgar se recorren sin tener que apuntar.
 */
@Composable
private fun KindFilters(
    available: List<PestKind>,
    selected: PestKind?,
    onSelect: (PestKind?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            text = stringResource(R.string.filtro_todas),
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        available.forEach { kind ->
            FilterPill(
                text = stringResource(kind.filterLabelRes()),
                selected = selected == kind,
                onClick = { onSelect(kind) },
            )
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) AgroTheme.semantic.accent else AgroTheme.semantic.card
    val content = if (selected) AgroTheme.semantic.onAccent else MaterialTheme.colorScheme.onSurface
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.Tab
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/**
 * Ficha en la cuadrícula.
 *
 * La **tira de severidad** va justo debajo de la foto, que es el elemento firma
 * del sistema (§11.4): permite escanear la cuadrícula y detectar el patrón sin
 * leer una sola etiqueta. La etiqueta de tipo va DEBAJO de la foto, nunca
 * encima: un color saturado sobre la hoja desplaza el tono percibido de la
 * lesión (§11.1).
 *
 * ⚠️ La severidad que muestra es la de la **escala típica de la plaga**, no la
 * de un caso concreto. Cuando exista una captura asociada, será la medida.
 */
@Composable
private fun PestTile(pest: PestTaxon, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AgroTheme.semantic.card)
            .clickable(onClick = onClick),
    ) {
        LocalImage(
            source = pest.imageRefs.firstOrNull().orEmpty(),
            contentDescription = null,
            targetPx = 512,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        SeverityStrip(
            level = pest.kind.indicativeSeverity(),
            density = SeverityStripDensity.NORMAL,
        )
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = pest.commonName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // Dos líneas siempre, para que las fichas de una misma fila
                // queden alineadas aunque un nombre sea corto.
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pest.scientificName,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = AgroTheme.semantic.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                KindPill(pest.kind)
            }
        }
    }
}

@Composable
internal fun KindPill(kind: PestKind) {
    StatusPill(
        text = stringResource(kind.labelRes()),
        // El tono NO es decorativo: una enfermedad y una deficiencia exigen
        // acciones distintas —fungicida contra fertilización— y confundirlas
        // hace que el productor fumigue una carencia de potasio.
        tone = when (kind) {
            PestKind.ENFERMEDAD -> StatusTone.CRITICAL
            PestKind.PLAGA -> StatusTone.WARNING
            PestKind.DEFICIENCIA -> StatusTone.NEUTRAL
            PestKind.SANO -> StatusTone.POSITIVE
            PestKind.OTRO -> StatusTone.NEUTRAL
        },
    )
}

internal fun PestKind.labelRes(): Int = when (this) {
    PestKind.ENFERMEDAD -> R.string.tipo_enfermedad
    PestKind.PLAGA -> R.string.tipo_plaga
    PestKind.DEFICIENCIA -> R.string.tipo_deficiencia
    PestKind.SANO -> R.string.tipo_sano
    PestKind.OTRO -> R.string.tipo_otro
}

private fun PestKind.filterLabelRes(): Int = when (this) {
    PestKind.ENFERMEDAD -> R.string.filtro_enfermedades
    PestKind.PLAGA -> R.string.filtro_plagas
    PestKind.DEFICIENCIA -> R.string.filtro_deficiencias
    PestKind.SANO -> R.string.filtro_sanas
    PestKind.OTRO -> R.string.filtro_otros
}

/** Severidad orientativa por tipo de problema, para la tira de la ficha.
 *  Pública porque Inicio la usa en su carrusel. */
fun PestKind.indicativeSeverity(): SeverityLevel = when (this) {
    PestKind.ENFERMEDAD -> SeverityLevel.ALTO
    PestKind.PLAGA -> SeverityLevel.MODERADO
    PestKind.DEFICIENCIA -> SeverityLevel.INCIPIENTE
    PestKind.SANO -> SeverityLevel.SANO
    PestKind.OTRO -> SeverityLevel.INCIPIENTE
}
