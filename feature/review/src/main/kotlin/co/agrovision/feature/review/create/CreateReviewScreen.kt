package co.agrovision.feature.review.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.SecondaryFieldButton
import co.agrovision.core.designsystem.text.resolve
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.feature.review.R
import co.agrovision.feature.review.components.ChoiceGroup
import co.agrovision.feature.review.components.FieldInput
import co.agrovision.feature.review.components.GUIDED_ANGLES
import co.agrovision.feature.review.components.InfoCard
import co.agrovision.feature.review.components.ReviewHero
import co.agrovision.feature.review.components.ReviewTopBar
import co.agrovision.feature.review.components.SectionTitle
import co.agrovision.feature.review.components.StepSegments
import co.agrovision.feature.review.components.guide
import co.agrovision.feature.review.components.label

/**
 * La cámara llega como parámetro desde `:app`, igual que en `ScanScreen`: así esta
 * pantalla no depende de `:feature:scan` y se puede previsualizar sin cámara.
 */
typealias GuidedCamera = @Composable (hint: String, onCapture: (RgbImage) -> Unit, modifier: Modifier) -> Unit

@Composable
fun CreateReviewScreen(
    state: CreateReviewState,
    onIntent: (CreateReviewIntent) -> Unit,
    camera: GuidedCamera,
    modifier: Modifier = Modifier,
) {
    BackHandler { onIntent(CreateReviewIntent.Cancel) }

    when (val stage = state.stage) {
        is CreateStage.Capturing -> GuidedCapture(stage.angle, state, onIntent, camera, modifier)
        is CreateStage.Saving -> SavingShot(modifier)
        is CreateStage.Confirming -> ConfirmShot(stage, state, onIntent, modifier)
        CreateStage.Form -> ReviewForm(state, onIntent, modifier)
    }
}

// ── Toma guiada ─────────────────────────────────────────────────────────────

/**
 * Cámara a sangre con la instrucción de la toma arriba.
 *
 * La tarjeta de arriba dice QUÉ fotografiar y por qué; la etiqueta de abajo, junto
 * al disparador, lo repite en corto. Arriba se lee antes de encuadrar, abajo se
 * lee con la mirada puesta en el disparador.
 */
@Composable
private fun GuidedCapture(
    angle: CaptureAngle,
    state: CreateReviewState,
    onIntent: (CreateReviewIntent) -> Unit,
    camera: GuidedCamera,
    modifier: Modifier,
) {
    val guide = angle.guide
    val semantic = AgroTheme.semantic
    val step = state.stepOf(angle)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        camera(
            stringResource(guide.cameraHint),
            { image -> onIntent(CreateReviewIntent.Captured(image)) },
            Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    onClick = { onIntent(CreateReviewIntent.Cancel) },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.guiada_cerrar),
                        tint = Color.White,
                    )
                }
                StepSegments(
                    total = GUIDED_ANGLES.size,
                    current = step,
                    onDark = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(semantic.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(guide.icon, contentDescription = null, tint = semantic.onAccent, modifier = Modifier.size(26.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.guiada_paso, step + 1, GUIDED_ANGLES.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = semantic.accent,
                    )
                    Text(
                        text = stringResource(guide.title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(guide.body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
            }

            state.error?.let {
                Text(
                    text = it.resolve(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SavingShot(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AgroTheme.semantic.specimenBackdrop),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = AgroTheme.semantic.divider,
            strokeWidth = 5.dp,
        )
        Text(stringResource(R.string.guardando_foto), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * La foto recién tomada, sobre el fondo neutro del banco: nada de color de marca
 * junto al espécimen (§11.1). Tres comprobaciones que el productor hace de un
 * vistazo antes de aceptar; son las mismas que haría la compuerta de calidad.
 */
@Composable
private fun ConfirmShot(
    stage: CreateStage.Confirming,
    state: CreateReviewState,
    onIntent: (CreateReviewIntent) -> Unit,
    modifier: Modifier,
) {
    val guide = stage.angle.guide
    val step = state.stepOf(stage.angle)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AgroTheme.semantic.specimenBackdrop)
            .verticalScroll(rememberScrollState())
            .padding(AgroTheme.dimensions.gutter),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
    ) {
        StepSegments(total = GUIDED_ANGLES.size, current = step)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.guiada_paso, step + 1, GUIDED_ANGLES.size) +
                    " · " + stringResource(guide.title),
                style = MaterialTheme.typography.labelLarge,
                color = AgroTheme.semantic.mutedText,
            )
            Text(stringResource(R.string.confirmar_titulo), style = MaterialTheme.typography.headlineMedium)
        }

        LocalImage(
            source = stage.photo.imagePath,
            contentDescription = stringResource(guide.title),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CheckLine(stringResource(R.string.confirmar_nitida))
            CheckLine(stringResource(R.string.confirmar_luz))
            CheckLine(stringResource(R.string.confirmar_encuadre) + ": " + stringResource(guide.title).lowercase())
        }

        PrimaryFieldButton(
            text = stringResource(R.string.confirmar_usar),
            icon = Icons.Filled.Check,
            onClick = { onIntent(CreateReviewIntent.AcceptShot) },
        )
        SecondaryFieldButton(
            text = stringResource(R.string.confirmar_repetir),
            icon = Icons.Filled.Replay,
            onClick = { onIntent(CreateReviewIntent.RetakeShot) },
        )
    }
}

@Composable
private fun CheckLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

// ── Formulario ──────────────────────────────────────────────────────────────

@Composable
private fun ReviewForm(
    state: CreateReviewState,
    onIntent: (CreateReviewIntent) -> Unit,
    modifier: Modifier,
) {
    val gutter = AgroTheme.dimensions.gutter
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        ReviewTopBar(
            title = stringResource(R.string.solicitud_titulo),
            onBack = { onIntent(CreateReviewIntent.Cancel) },
            backDescription = stringResource(R.string.solicitud_atras),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = AgroTheme.dimensions.gutterWide),
            verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterWide),
        ) {
            ReviewHero(
                title = stringResource(R.string.solicitud_hero_titulo),
                body = stringResource(R.string.solicitud_hero_cuerpo),
                chip = stringResource(R.string.solicitud_fotos_listas, state.photoCount),
                modifier = Modifier.padding(horizontal = gutter),
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(
                    text = stringResource(R.string.solicitud_tus_fotos),
                    supporting = stringResource(R.string.solicitud_toca_repetir),
                    modifier = Modifier.padding(horizontal = gutter),
                )
                PhotoStrip(state = state, onRetake = { onIntent(CreateReviewIntent.RetakeAngle(it)) })
            }

            Column(
                modifier = Modifier.padding(horizontal = gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.solicitud_cultivo))
                ChoiceGroup(
                    options = CropType.entries,
                    selected = state.cropType,
                    label = { it.label() },
                    onSelect = { onIntent(CreateReviewIntent.SetCrop(it)) },
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.solicitud_parte))
                ChoiceGroup(
                    options = PlantOrgan.entries,
                    selected = state.plantOrgan,
                    label = { it.label() },
                    onSelect = { onIntent(CreateReviewIntent.SetOrgan(it)) },
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.solicitud_nota))
                FieldInput(
                    value = state.note,
                    onValueChange = { onIntent(CreateReviewIntent.SetNote(it)) },
                    label = stringResource(R.string.solicitud_nota),
                    placeholder = stringResource(R.string.solicitud_nota_ayuda),
                    singleLine = false,
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            if (state.variety != null && state.plantingAge != null) {
                InfoCard(
                    icon = Icons.Filled.Eco,
                    title = stringResource(R.string.solicitud_cafetal),
                    body = stringResource(
                        R.string.solicitud_cafetal_valor,
                        state.variety.label,
                        state.plantingAge.label.lowercase(),
                    ),
                    modifier = Modifier.padding(horizontal = gutter),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let {
                    Text(
                        text = it.resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PrimaryFieldButton(
                    text = stringResource(
                        if (state.submitting) R.string.solicitud_guardando else R.string.solicitud_enviar
                    ),
                    icon = Icons.AutoMirrored.Filled.Send,
                    enabled = !state.submitting,
                    onClick = { onIntent(CreateReviewIntent.Submit) },
                )
            }
        }
    }
}

/**
 * Las cinco fotos en fila, cada una con su nombre DEBAJO y no encima: cualquier
 * color sobre la imagen desplaza el tono percibido de la lesión (§11.1).
 */
@Composable
private fun PhotoStrip(state: CreateReviewState, onRetake: (CaptureAngle) -> Unit) {
    val gutter = AgroTheme.dimensions.gutter
    val tiles = buildList {
        state.original?.let { add(PhotoTileModel(it, stringResource(R.string.angulo_original), null)) }
        GUIDED_ANGLES.forEach { angle ->
            state.shots[angle]?.let { add(PhotoTileModel(it, stringResource(angle.guide.shortLabel), angle)) }
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tiles, key = { it.photo.id }) { tile ->
            Column(
                modifier = Modifier
                    .width(PHOTO_TILE_WIDTH)
                    .clip(MaterialTheme.shapes.medium)
                    .background(AgroTheme.semantic.card)
                    .then(
                        if (tile.angle != null) Modifier.clickable { onRetake(tile.angle) } else Modifier
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(AgroTheme.semantic.specimenBackdrop),
                ) {
                    LocalImage(
                        source = tile.photo.thumbnailPath,
                        contentDescription = tile.label,
                        targetPx = 384,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (tile.angle != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Replay, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Text(
                    text = tile.label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

private data class PhotoTileModel(val photo: ReviewPhoto, val label: String, val angle: CaptureAngle?)

private val PHOTO_TILE_WIDTH = 120.dp
