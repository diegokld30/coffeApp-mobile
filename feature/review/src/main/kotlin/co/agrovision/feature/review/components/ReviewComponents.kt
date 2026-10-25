package co.agrovision.feature.review.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.LeafCluster
import co.agrovision.core.designsystem.theme.AgroTheme

/**
 * Piezas comunes de las pantallas de solicitud e ingreso.
 *
 * Siguen el lenguaje de Inicio: una pieza protagonista con el degradado de marca
 * y el racimo de hojas, tarjetas que se separan del fondo por claridad y no por
 * sombra, y la lima reservada a lo que está elegido o es la acción principal.
 */

/** Barra superior mínima: volver y título. Sin barra de app de Material: en el
 *  resto de la app el título va sobre el fondo, no dentro de una franja. */
@Composable
fun ReviewTopBar(
    title: String,
    onBack: () -> Unit,
    backDescription: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AgroTheme.semantic.card),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        trailing?.invoke()
    }
}

/**
 * Pieza protagonista: degradado de marca con el racimo de hojas.
 *
 * ⚠️ Es decoración de marca. Nunca junto a una imagen de espécimen (§11.1): las
 * pantallas la separan de las fotos con el espaciado ancho.
 */
@Composable
fun ReviewHero(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    chip: String? = null,
) {
    val semantic = AgroTheme.semantic
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(semantic.heroStart, semantic.heroEnd)))
            .border(1.dp, semantic.heroBorder, MaterialTheme.shapes.extraLarge),
    ) {
        LeafCluster(
            leaf = semantic.accent.copy(alpha = 0.10f),
            leafStrong = semantic.accent.copy(alpha = 0.18f),
            accent = semantic.accent.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 34.dp, y = 10.dp)
                .size(150.dp),
        )
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chip?.let { LimeChip(text = it) }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = semantic.heroContent,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.heroContent.copy(alpha = 0.8f),
            )
        }
    }
}

/** Etiqueta en lima: lo único saturado dentro de la pieza protagonista. */
@Composable
fun LimeChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = AgroTheme.semantic.onAccent,
        modifier = modifier
            .clip(CircleShape)
            .background(AgroTheme.semantic.accent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = text, style = MaterialTheme.typography.titleLarge)
        supporting?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }
    }
}

/**
 * Grupo de opciones como pastillas. Lo elegido va en lima con marca de verificación:
 * el estado nunca depende solo del color (§11.4).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceGroup(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            ChoicePill(
                text = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val semantic = AgroTheme.semantic
    val haptics = LocalHapticFeedback.current
    val background = if (selected) semantic.accent else semantic.card
    val content = if (selected) semantic.onAccent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = AgroTheme.dimensions.minTouchTarget)
            .clip(CircleShape)
            .background(background)
            .border(
                width = AgroTheme.dimensions.borderWidth,
                color = if (selected) semantic.accent else semantic.divider,
                shape = CircleShape,
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Campo de texto sobre tarjeta, con el radio mediano de la app. */
@Composable
fun FieldInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    leading: (@Composable () -> Unit)? = null,
) {
    val semantic = AgroTheme.semantic
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = semantic.mutedText) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        leadingIcon = leading,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.card,
            unfocusedContainerColor = semantic.card,
            errorContainerColor = semantic.card,
            focusedBorderColor = semantic.accent,
            unfocusedBorderColor = semantic.divider,
            focusedLabelColor = semantic.accent,
            cursorColor = semantic.accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Fila de tarjeta con icono en círculo, título y texto de apoyo. */
@Composable
fun InfoCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = AgroTheme.semantic.onSoftLime,
    iconBackground: androidx.compose.ui.graphics.Color = AgroTheme.semantic.softLime,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AgroTheme.semantic.card)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = AgroTheme.semantic.mutedText)
        }
        trailing?.let {
            Spacer(Modifier.size(4.dp))
            it()
        }
    }
}

/** Progreso por pasos: segmentos que se llenan en lima. Se lee sin números,
 *  pero el número va igual en el texto de al lado. */
@Composable
fun StepSegments(total: Int, current: Int, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val semantic = AgroTheme.semantic
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            val color = when {
                index < current -> semantic.accent
                index == current -> if (onDark) androidx.compose.ui.graphics.Color.White else semantic.dataText
                else -> if (onDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f) else semantic.divider
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
