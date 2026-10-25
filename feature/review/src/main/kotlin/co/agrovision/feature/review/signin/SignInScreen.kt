package co.agrovision.feature.review.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.text.resolve
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.identity.CoffeeVariety
import co.agrovision.core.model.identity.PlantingAge
import co.agrovision.feature.review.R
import co.agrovision.feature.review.components.ChoiceGroup
import co.agrovision.feature.review.components.FieldInput
import co.agrovision.feature.review.components.ReviewHero
import co.agrovision.feature.review.components.ReviewTopBar
import co.agrovision.feature.review.components.SectionTitle

/**
 * Ingreso en tres pasos: celular, código y finca.
 *
 * Mismo esqueleto en los tres —protagonista arriba, lo que hay que llenar debajo,
 * un solo botón principal al final— para que cambiar de paso no se sienta como
 * cambiar de app.
 */
@Composable
fun SignInScreen(
    state: SignInState,
    onIntent: (SignInIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onIntent(SignInIntent.Back) }
    val gutter = AgroTheme.dimensions.gutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        ReviewTopBar(
            title = stringResource(R.string.ingreso_paso, state.step.ordinal + 1, SignInStep.entries.size),
            onBack = { onIntent(SignInIntent.Back) },
            backDescription = stringResource(R.string.solicitud_atras),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = gutter, end = gutter, bottom = AgroTheme.dimensions.gutterWide),
            verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterWide),
        ) {
            when (state.step) {
                SignInStep.PHONE -> PhoneStep(state, onIntent)
                SignInStep.CODE -> CodeStep(state, onIntent)
                SignInStep.PROFILE -> ProfileStep(state, onIntent)
            }
        }
    }
}

@Composable
private fun PhoneStep(state: SignInState, onIntent: (SignInIntent) -> Unit) {
    ReviewHero(
        title = stringResource(R.string.ingreso_telefono_titulo),
        body = stringResource(R.string.ingreso_telefono_cuerpo),
    )
    FieldInput(
        value = state.phoneDigits,
        onValueChange = { onIntent(SignInIntent.SetPhone(it)) },
        label = stringResource(R.string.ingreso_telefono_campo),
        placeholder = "300 123 4567",
        error = if (state.phoneInvalid) stringResource(R.string.ingreso_telefono_invalido) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.titleLarge,
        leading = {
            Text(
                text = stringResource(R.string.ingreso_telefono_prefijo),
                style = MaterialTheme.typography.titleMedium,
                color = AgroTheme.semantic.accent,
                modifier = Modifier.padding(start = 12.dp),
            )
        },
    )
    ErrorText(state)
    PrimaryFieldButton(
        text = stringResource(R.string.ingreso_enviar_codigo),
        icon = Icons.Filled.Sms,
        enabled = !state.busy,
        onClick = { onIntent(SignInIntent.SendCode) },
    )
}

@Composable
private fun CodeStep(state: SignInState, onIntent: (SignInIntent) -> Unit) {
    ReviewHero(
        title = stringResource(R.string.ingreso_codigo_titulo),
        body = stringResource(R.string.ingreso_codigo_cuerpo, "+57 " + state.formattedPhone),
    )
    FieldInput(
        value = state.code,
        onValueChange = { onIntent(SignInIntent.SetCode(it)) },
        label = stringResource(R.string.ingreso_codigo_campo),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 10.sp, textAlign = TextAlign.Center),
    )
    ErrorText(state)
    PrimaryFieldButton(
        text = stringResource(R.string.ingreso_confirmar),
        icon = Icons.Filled.Check,
        enabled = !state.busy && state.code.length == 6,
        onClick = { onIntent(SignInIntent.Verify) },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val canResend = state.resendSeconds == 0 && !state.busy
        LinkText(
            text = if (state.resendSeconds > 0) {
                stringResource(R.string.ingreso_reenviar_en, state.resendSeconds)
            } else {
                stringResource(R.string.ingreso_reenviar)
            },
            enabled = canResend,
            onClick = { onIntent(SignInIntent.ResendCode) },
        )
        LinkText(
            text = stringResource(R.string.ingreso_cambiar_numero),
            enabled = !state.busy,
            onClick = { onIntent(SignInIntent.ChangeNumber) },
        )
    }
}

@Composable
private fun ProfileStep(state: SignInState, onIntent: (SignInIntent) -> Unit) {
    val form = state.profile
    val invalid = state.invalidFields
    val fieldError = stringResource(R.string.ingreso_campo_invalido)
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)

    ReviewHero(
        title = stringResource(R.string.ingreso_perfil_titulo),
        body = stringResource(R.string.ingreso_perfil_cuerpo),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.ingreso_seccion_datos))
        FieldInput(
            value = form.fullName,
            onValueChange = { v -> onIntent(SignInIntent.EditProfile { it.copy(fullName = v) }) },
            label = stringResource(R.string.ingreso_nombre),
            error = fieldError.takeIf { ProfileField.FULL_NAME in invalid },
            keyboardOptions = words,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.ingreso_seccion_ubicacion))
        FieldInput(
            value = form.farmName,
            onValueChange = { v -> onIntent(SignInIntent.EditProfile { it.copy(farmName = v) }) },
            label = stringResource(R.string.ingreso_finca),
            error = fieldError.takeIf { ProfileField.FARM_NAME in invalid },
            keyboardOptions = words,
        )
        FieldInput(
            value = form.vereda,
            onValueChange = { v -> onIntent(SignInIntent.EditProfile { it.copy(vereda = v) }) },
            label = stringResource(R.string.ingreso_vereda),
            error = fieldError.takeIf { ProfileField.VEREDA in invalid },
            keyboardOptions = words,
        )
        FieldInput(
            value = form.municipality,
            onValueChange = { v -> onIntent(SignInIntent.EditProfile { it.copy(municipality = v) }) },
            label = stringResource(R.string.ingreso_municipio),
            error = fieldError.takeIf { ProfileField.MUNICIPALITY in invalid },
            keyboardOptions = words.copy(imeAction = ImeAction.Done),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.ingreso_seccion_cafetal))
        ChoiceQuestion(
            title = stringResource(R.string.ingreso_variedad),
            missing = ProfileField.VARIETY in invalid,
        ) {
            ChoiceGroup(
                options = CoffeeVariety.entries,
                selected = form.variety,
                label = { it.label },
                onSelect = { v -> onIntent(SignInIntent.EditProfile { it.copy(variety = v) }) },
            )
        }
        ChoiceQuestion(
            title = stringResource(R.string.ingreso_edad),
            missing = ProfileField.PLANTING_AGE in invalid,
        ) {
            ChoiceGroup(
                options = PlantingAge.entries,
                selected = form.plantingAge,
                label = { it.label },
                onSelect = { v -> onIntent(SignInIntent.EditProfile { it.copy(plantingAge = v) }) },
            )
        }
    }

    ConsentCard(
        checked = form.trainingConsent,
        onCheckedChange = { v -> onIntent(SignInIntent.EditProfile { it.copy(trainingConsent = v) }) },
    )

    ErrorText(state)
    PrimaryFieldButton(
        text = stringResource(R.string.ingreso_guardar),
        icon = Icons.AutoMirrored.Filled.Send,
        enabled = !state.busy,
        onClick = { onIntent(SignInIntent.SaveProfile) },
    )
}

@Composable
private fun ChoiceQuestion(title: String, missing: Boolean, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        content()
        if (missing) {
            Text(
                text = stringResource(R.string.ingreso_elige_opcion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** El consentimiento, como tarjeta con interruptor: se lee entero antes de tocar. */
@Composable
private fun ConsentCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val semantic = AgroTheme.semantic
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(semantic.card)
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ingreso_consentimiento_titulo), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.ingreso_consentimiento_cuerpo),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.mutedText,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = semantic.accent,
                checkedThumbColor = semantic.onAccent,
            ),
        )
    }
}

@Composable
private fun LinkText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else AgroTheme.semantic.mutedText,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ErrorText(state: SignInState) {
    state.error?.let {
        Text(
            text = it.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
