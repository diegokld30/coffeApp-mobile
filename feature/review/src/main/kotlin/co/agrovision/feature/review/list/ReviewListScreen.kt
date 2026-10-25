package co.agrovision.feature.review.list

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.StatusPill
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.feature.review.R
import co.agrovision.feature.review.components.InfoCard
import co.agrovision.feature.review.components.displayStatus
import co.agrovision.feature.review.components.label
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ReviewListScreen(
    state: ReviewListState,
    onToggle: (ReviewRequest) -> Unit,
    onSendNow: () -> Unit,
    onSignIn: () -> Unit,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = AgroTheme.dimensions.gutter
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = AgroTheme.dimensions.gutterWide),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "cabecera") {
            Column(
                modifier = Modifier
                    .padding(top = gutter, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.lista_titulo), style = MaterialTheme.typography.displaySmall)
                Text(
                    text = stringResource(R.string.lista_subtitulo),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AgroTheme.semantic.mutedText,
                )
            }
        }

        if (state.sessionExpired) {
            item(key = "sesion") {
                InfoCard(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.lista_sesion_titulo),
                    body = stringResource(R.string.lista_sesion_cuerpo),
                    iconBackground = AgroTheme.semantic.softSky,
                    tint = AgroTheme.semantic.onSoftSky,
                    trailing = { CompactAction(stringResource(R.string.lista_entrar), onClick = onSignIn) },
                )
            }
        }

        if (state.pendingCount > 0 && !state.sessionExpired) {
            item(key = "pendientes") {
                InfoCard(
                    icon = Icons.Filled.CloudUpload,
                    title = stringResource(R.string.lista_pendientes, state.pendingCount),
                    body = stringResource(R.string.lista_pendientes_cuerpo),
                    trailing = {
                        if (state.sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = AgroTheme.semantic.accent,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            CompactAction(stringResource(R.string.lista_enviar_ahora), onClick = onSendNow)
                        }
                    },
                )
            }
        }

        if (!state.loading && state.requests.isEmpty()) {
            item(key = "vacio") {
                EmptyState(
                    title = stringResource(R.string.lista_vacia_titulo),
                    body = stringResource(R.string.lista_vacia_cuerpo),
                    icon = Icons.Filled.Forum,
                    action = {
                        PrimaryFieldButton(
                            text = stringResource(R.string.lista_analizar),
                            icon = Icons.Filled.PhotoCamera,
                            onClick = onAnalyze,
                        )
                    },
                )
            }
        }

        items(state.requests, key = { it.id }) { request ->
            RequestCard(
                request = request,
                unread = request.id in state.unreadIds,
                expanded = request.id == state.expandedId,
                onClick = { onToggle(request) },
            )
        }
    }
}

/**
 * Una solicitud. La miniatura va sobre el fondo neutro y separada del texto: nada
 * de color de marca pegado al espécimen (§11.1). La respuesta del agrónomo se
 * adelanta en dos líneas y se lee entera al tocar.
 */
@Composable
private fun RequestCard(
    request: ReviewRequest,
    unread: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val semantic = AgroTheme.semantic
    val status = request.displayStatus
    val resolution = request.resolution

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(semantic.card)
            .clickable(onClick = onClick)
            .animateContentSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .clip(MaterialTheme.shapes.small)
                    .background(semantic.specimenBackdrop),
            ) {
                request.images.firstOrNull()?.let {
                    LocalImage(
                        source = it.thumbnailPath,
                        contentDescription = null,
                        targetPx = 256,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${request.cropType.label()} · ${request.plantOrgan.label()}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unread) {
                        Text(
                            text = stringResource(R.string.lista_nueva),
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.onAccent,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(semantic.accent)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    text = formatDate(request.createdAtEpochMillis) + " · " +
                        stringResource(R.string.lista_fotos, request.images.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.mutedText,
                )
                StatusPill(text = stringResource(status.text), tone = status.tone)
            }
        }

        if (resolution != null) {
            HorizontalDivider(color = semantic.divider)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.lista_respuesta),
                    style = MaterialTheme.typography.labelLarge,
                    color = semantic.accent,
                )
                Text(stringResource(resolution.type.label), style = MaterialTheme.typography.titleMedium)
                if (resolution.agronomistResponse.isNotBlank()) {
                    Text(
                        text = resolution.agronomistResponse,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (expanded && request.sync.state == SyncState.FAILED && request.sync.lastError != null) {
            Text(
                text = request.sync.lastError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.mutedText,
            )
        }

        if (expanded && !request.noteText.isNullOrBlank()) {
            Text(
                text = request.noteText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.mutedText,
            )
        }
    }
}

/** Acción pequeña en lima para dentro de una tarjeta. */
@Composable
private fun CompactAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(AgroTheme.semantic.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = AgroTheme.semantic.onAccent)
    }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es-CO"))

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)

private val THUMB_SIZE = 72.dp
