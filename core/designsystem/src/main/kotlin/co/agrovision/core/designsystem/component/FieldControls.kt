package co.agrovision.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.theme.AgroColors
import co.agrovision.core.designsystem.theme.AgroTheme

/**
 * Controles pensados para el contexto real: a pleno sol, de pie, con una mano,
 * con guantes o manos sucias (§2.1).
 *
 * Tres consecuencias que atraviesan todo lo de aquí:
 *  · Objetivos táctiles muy por encima del mínimo de accesibilidad.
 *  · Retroalimentación háptica en toda acción, porque bajo el sol la
 *    confirmación visual puede no percibirse.
 *  · Iconografía acompañada de texto, nunca sustituyéndolo: la alfabetización
 *    digital del público es variable, pero la iconografía sola es peor, no mejor.
 */

/**
 * Acción principal de una pantalla. Un solo botón, grande, siempre en el mismo
 * sitio. El flujo de un solo botón es un requisito derivado del perfil de
 * usuario, no una simplificación estética.
 */
@Composable
fun PrimaryFieldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) }
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun SecondaryFieldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    OutlinedButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = maxOf(56.dp, AgroTheme.dimensions.minTouchTarget)),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(
            width = AgroTheme.dimensions.borderWidth * 1.5f,
            color = MaterialTheme.colorScheme.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * El disparador de la cámara. 96 dp, circular, centrado abajo.
 *
 * Deliberadamente enorme y en la posición donde cae el pulgar sin recolocar la
 * mano. Es el único control que el productor acciona sin mirar la pantalla.
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val size = AgroTheme.dimensions.primaryAction
    val ring = if (enabled) Color.White else ShutterDisabledRing

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(width = 4.dp, color = ring, shape = CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(ring)
            // Sin `indication`: el destello del ripple sobre un botón blanco a
            // pleno sol no se ve, y la confirmación real es háptica.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = contentDescription,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            // `semantics` y no `clearAndSetSemantics`: este último borraría la
            // acción de clic que acaba de declarar `clickable`, y TalkBack
            // dejaría de poder disparar la cámara.
            .semantics { this.contentDescription = contentDescription },
    )
}

private val ShutterDisabledRing = Color(0x66FFFFFF)

/**
 * Estado vacío que invita a actuar (§11.8).
 *
 * Un estado vacío que solo dice «no hay nada» desperdicia el único momento en el
 * que el usuario está mirando esa pantalla sin distracciones.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AgroTheme.dimensions.gutterWide),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Centrado vertical: cuando ocupa toda la pantalla, el mensaje queda
        // donde cae la mirada y no pegado arriba.
        verticalArrangement = Arrangement.spacedBy(
            AgroTheme.dimensions.gutter,
            Alignment.CenterVertically,
        ),
    ) {
        icon?.let {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = AgroTheme.semantic.mutedText,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** Etiqueta de estado. Nunca solo color: siempre lleva texto. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    val background = when (tone) {
        StatusTone.NEUTRAL -> AgroTheme.semantic.divider
        StatusTone.POSITIVE -> AgroTheme.semantic.severity.level0
        StatusTone.WARNING -> AgroTheme.semantic.severity.level2
        StatusTone.CRITICAL -> AgroTheme.semantic.severity.level4
    }
    // Los tonos claros de la rampa llevan siempre tinta oscura, sea cual sea
    // el tema: texto claro sobre amarillo o verde pálido no se lee.
    val content = when (tone) {
        StatusTone.NEUTRAL -> AgroTheme.semantic.dataText
        StatusTone.CRITICAL -> Color.White
        StatusTone.POSITIVE, StatusTone.WARNING -> AgroColors.Bench900
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides content) {
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

enum class StatusTone { NEUTRAL, POSITIVE, WARNING, CRITICAL }
