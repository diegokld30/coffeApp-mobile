package co.agrovision.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.pest.PlantOrgan

/**
 * Ficha técnica de una plaga.
 *
 * El orden de las secciones sigue el de las preguntas del productor: qué es,
 * cómo se ve, dónde aparece, qué hago. No el orden en que resulta cómodo
 * almacenarlo.
 *
 * ## Lo que esta pantalla se niega a mostrar
 *
 * Si la ficha no tiene aval de un ingeniero agrónomo con tarjeta vigente, la
 * sección «Qué hacer» **no se muestra** — y en su lugar se explica por qué falta.
 * Ocultarla sin explicación haría pensar que no hay tratamiento; mostrarla sin
 * aval es exposición civil e incumplimiento ante el ICA (§13.6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PestDetailScreen(
    pest: PestTaxon?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pest == null) {
        Box(modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = AgroTheme.dimensions.gutterWide),
    ) {
        item {
            Box {
                LocalImage(
                    source = pest.imageRefs.firstOrNull().orEmpty(),
                    contentDescription = pest.commonName,
                    targetPx = 1024,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(
                            RoundedCornerShape(
                                bottomStart = AgroTheme.dimensions.heroRadius,
                                bottomEnd = AgroTheme.dimensions.heroRadius,
                            ),
                        ),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(AgroTheme.dimensions.gutter)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AgroTheme.semantic.card),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ficha_volver),
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(
                    start = AgroTheme.dimensions.gutter,
                    end = AgroTheme.dimensions.gutter,
                    top = AgroTheme.dimensions.gutterWide,
                    bottom = AgroTheme.dimensions.gutter,
                ),
                verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
            ) {
                Text(pest.commonName, style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = pest.scientificName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = AgroTheme.semantic.mutedText,
                )
                KindPill(pest.kind)
            }
        }

        // Galería. Solo si hay más de una imagen: una sola ya está en la cabecera.
        if (pest.imageRefs.size > 1) {
            item {
                LazyRow(
                    modifier = Modifier.padding(bottom = AgroTheme.dimensions.gutter),
                    contentPadding = PaddingValues(horizontal = AgroTheme.dimensions.gutter),
                    horizontalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
                ) {
                    items(pest.imageRefs.drop(1)) { ref ->
                        LocalImage(
                            source = ref,
                            contentDescription = null,
                            targetPx = 512,
                            modifier = Modifier
                                .size(GALLERY_ITEM)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    }
                }
            }
        }

        if (pest.description.isNotBlank()) {
            item { Section(title = stringResource(R.string.ficha_sintomas), body = pest.description) }
        }

        if (pest.affectedOrgans.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = AgroTheme.dimensions.gutter,
                        vertical = AgroTheme.dimensions.gutterTight,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
                ) {
                    Text(
                        stringResource(R.string.ficha_organos),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pest.affectedOrgans.forEach { organ ->
                            Text(
                                text = stringResource(organ.labelRes()),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            if (pest.showsRecommendations) {
                Column(
                    modifier = Modifier
                        .padding(
                            horizontal = AgroTheme.dimensions.gutter,
                            vertical = AgroTheme.dimensions.gutterTight,
                        )
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(AgroTheme.semantic.card)
                        .padding(AgroTheme.dimensions.gutter),
                    verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
                ) {
                    Text(
                        stringResource(R.string.ficha_manejo),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    pest.recommendations.forEach { recommendation ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 9.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary),
                            )
                            Text(
                                text = recommendation.detail.ifBlank { recommendation.summary },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    pest.approval?.let {
                        Text(
                            text = "Avalado por ${it.agronomistName} — T.P. ${it.licenseNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AgroTheme.semantic.mutedText,
                        )
                    }
                }
            } else {
                PendingApprovalNotice()
            }
        }

        pest.reference?.let { reference ->
            item {
                Section(
                    title = stringResource(R.string.ficha_referencia),
                    body = reference,
                    small = true,
                )
            }
        }
    }
}

/**
 * Aviso de recomendaciones sin aval.
 *
 * Se muestra en lugar de la sección de manejo. Explica la ausencia en vez de
 * dejar un hueco: un hueco se lee como «no hay tratamiento», y esto es «lo hay,
 * falta que un profesional lo firme».
 */
@Composable
private fun PendingApprovalNotice() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AgroTheme.dimensions.gutter),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(AgroTheme.dimensions.gutter),
            horizontalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.ficha_manejo_sin_aval_titulo),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.ficha_manejo_sin_aval_cuerpo),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String, small: Boolean = false) {
    Column(
        modifier = Modifier.padding(
            horizontal = AgroTheme.dimensions.gutter,
            vertical = AgroTheme.dimensions.gutterTight,
        ),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = body,
            style = if (small) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodyLarge,
            color = if (small) AgroTheme.semantic.mutedText else MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun PlantOrgan.labelRes(): Int = when (this) {
    PlantOrgan.HOJA -> R.string.organo_hoja
    PlantOrgan.FRUTO -> R.string.organo_fruto
    PlantOrgan.TALLO -> R.string.organo_tallo
    PlantOrgan.RAIZ -> R.string.organo_raiz
    PlantOrgan.FLOR -> R.string.organo_flor
}

private val GALLERY_ITEM = 128.dp
