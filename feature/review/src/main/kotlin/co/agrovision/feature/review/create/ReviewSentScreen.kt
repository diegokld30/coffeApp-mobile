package co.agrovision.feature.review.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.LeafCluster
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.SecondaryFieldButton
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.feature.review.R

/**
 * Cierre del envío. Una sola pieza protagonista con el resultado y dos salidas.
 *
 * «Quedó guardada» no se pinta como error: sin señal es el estado normal en el
 * lote, y lo que el productor necesita oír es que su trabajo está a salvo.
 */
@Composable
fun ReviewSentScreen(
    outcome: SentOutcome,
    onSeeRequests: () -> Unit,
    onHome: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = AgroTheme.semantic
    val (title, body) = when (outcome) {
        SentOutcome.SENDING -> R.string.enviada_enviando to R.string.enviada_enviando_cuerpo
        SentOutcome.DELIVERED -> R.string.enviada_lista to R.string.enviada_lista_cuerpo
        SentOutcome.QUEUED -> R.string.enviada_guardada to R.string.enviada_guardada_cuerpo
        SentOutcome.SIGN_IN_REQUIRED -> R.string.enviada_sesion to R.string.enviada_sesion_cuerpo
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(AgroTheme.dimensions.gutter),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(Brush.linearGradient(listOf(semantic.heroStart, semantic.heroEnd))),
        ) {
            LeafCluster(
                leaf = semantic.accent.copy(alpha = 0.10f),
                leafStrong = semantic.accent.copy(alpha = 0.18f),
                accent = semantic.accent.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 40.dp, y = 12.dp)
                    .size(200.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutcomeBadge(outcome)
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = semantic.heroContent,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = semantic.heroContent.copy(alpha = 0.82f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (outcome == SentOutcome.SIGN_IN_REQUIRED) {
            PrimaryFieldButton(
                text = stringResource(R.string.enviada_entrar),
                icon = Icons.Filled.Sms,
                onClick = onSignIn,
            )
        } else {
            PrimaryFieldButton(
                text = stringResource(R.string.enviada_ver_solicitudes),
                icon = Icons.AutoMirrored.Filled.ListAlt,
                onClick = onSeeRequests,
            )
        }
        SecondaryFieldButton(
            text = stringResource(R.string.enviada_inicio),
            icon = Icons.Filled.Home,
            onClick = onHome,
        )
    }
}

@Composable
private fun OutcomeBadge(outcome: SentOutcome) {
    val semantic = AgroTheme.semantic
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(semantic.accent),
        contentAlignment = Alignment.Center,
    ) {
        if (outcome == SentOutcome.SENDING) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                color = semantic.onAccent,
                trackColor = semantic.onAccent.copy(alpha = 0.2f),
                strokeWidth = 4.dp,
            )
        } else {
            Icon(
                imageVector = when (outcome) {
                    SentOutcome.DELIVERED -> Icons.Filled.Check
                    SentOutcome.QUEUED -> Icons.Filled.CloudQueue
                    else -> Icons.Filled.Lock
                },
                contentDescription = null,
                tint = semantic.onAccent,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}
