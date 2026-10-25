package co.agrovision.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.agrovision.core.common.AgroConstants

/**
 * Tres apariencias, no dos.
 *
 * La tercera —modo exterior— no es un capricho: el contexto de uso descrito en
 * §2.1 es «a pleno sol, de pie en el lote, con una mano, guantes o manos
 * sucias». Bajo luz solar directa la pantalla de un teléfono de gama baja pierde
 * buena parte de su contraste efectivo, y una interfaz que se lee perfectamente
 * en una oficina se vuelve ilegible ahí.
 */
enum class AgroAppearance {
    LIGHT,
    DARK,
    OUTDOOR,
}

@Immutable
data class AgroDimensions(
    val minTouchTarget: Dp = AgroConstants.Ui.MIN_TOUCH_TARGET_DP.dp,
    /** El disparador de la cámara. Mucho mayor que el mínimo de accesibilidad:
     *  se acciona con una mano, sin mirar, posiblemente con guantes. */
    val primaryAction: Dp = AgroConstants.Ui.PRIMARY_ACTION_DP.dp,
    val gutter: Dp = 16.dp,
    val gutterTight: Dp = 8.dp,
    val gutterWide: Dp = 24.dp,
    val cardRadius: Dp = 12.dp,
    /** Radio de la pieza protagonista de una pantalla. Más grande que el de las
     *  tarjetas a propósito: la jerarquía también se lee en la forma. */
    val heroRadius: Dp = 32.dp,
    val severityNotchHeight: Dp = 3.dp,
    val severityNotchHeightProminent: Dp = 5.dp,
    val borderWidth: Dp = 1.dp,
)

@Immutable
data class AgroSemanticColors(
    val severity: SeverityRamp,
    val spark: Color,
    val dataText: Color,
    val mutedText: Color,
    val divider: Color,
    /**
     * Fondo obligatorio detrás de cualquier imagen de espécimen. Neutro por
     * contrato: si alguien pone marca aquí, la percepción del tono de la lesión
     * se desplaza (§11.1).
     */
    val specimenBackdrop: Color,
    /** Degradado de la pieza protagonista y el texto que va encima. */
    val heroStart: Color,
    val heroEnd: Color,
    val heroContent: Color,
    /** Resplandor detrás de las hojas y borde fino de la pieza protagonista.
     *  Transparentes en claro; en oscuro son lo que la hace ver «premium». */
    val heroGlow: Color,
    val heroBorder: Color,
    /** Acento de la acción más importante (lima) y su tinta. */
    val accent: Color,
    val onAccent: Color,
    /** Tarjetas sobre el fondo de la interfaz. Se separan por claridad, no por
     *  sombra: la sombra desaparece a pleno sol, el contraste de fondo no. */
    val card: Color,
    /** Hoja elevada sobre el fondo neutro del banco (resultados de escaneo). */
    val sheet: Color,
    /** Pasteles de apoyo para iconos y consejos. */
    val softLime: Color,
    val onSoftLime: Color,
    val softSky: Color,
    val onSoftSky: Color,
)

private val LightSemantic = AgroSemanticColors(
    severity = SeverityRamp.Standard,
    spark = AgroColors.Spark400,
    dataText = AgroColors.Bench900,
    mutedText = AgroColors.Bench600,
    divider = AgroColors.Bench200,
    specimenBackdrop = AgroColors.Bench100,
    heroStart = AgroColors.HeroStart,
    heroEnd = AgroColors.HeroEnd,
    heroContent = Color.White,
    heroGlow = Color.Transparent,
    heroBorder = Color.Transparent,
    accent = AgroColors.Lime,
    onAccent = AgroColors.LimeInk,
    card = AgroColors.Card,
    sheet = Color.White,
    softLime = AgroColors.LimeSoft,
    onSoftLime = AgroColors.LimeInk,
    softSky = AgroColors.Sky,
    onSoftSky = AgroColors.SkyInk,
)

val LocalAgroDimensions = staticCompositionLocalOf { AgroDimensions() }

val LocalAgroSemanticColors = staticCompositionLocalOf { LightSemantic }

val LocalAgroAppearance = staticCompositionLocalOf { AgroAppearance.LIGHT }

private val LightScheme = lightColorScheme(
    primary = AgroColors.Brand500,
    onPrimary = Color.White,
    primaryContainer = AgroColors.Brand50,
    onPrimaryContainer = AgroColors.Brand600,
    secondary = AgroColors.Brand500,
    onSecondary = Color.White,
    secondaryContainer = AgroColors.Brand50,
    onSecondaryContainer = AgroColors.Brand600,
    background = Color.White,
    onBackground = AgroColors.Bench900,
    surface = Color.White,
    surfaceContainer = Color.White,
    onSurface = AgroColors.Bench900,
    surfaceVariant = AgroColors.Card,
    onSurfaceVariant = AgroColors.Bench600,
    outline = AgroColors.Bench300,
    outlineVariant = AgroColors.Bench200,
    error = AgroColors.Error,
    onError = Color.White,
)

/**
 * Oscuro premium: la apariencia por defecto.
 *
 * La lima hace de primario: es el único color saturado de la pantalla y por
 * eso la acción principal se encuentra sin buscarla.
 */
private val DarkScheme = darkColorScheme(
    primary = AgroColors.LimeBright,
    onPrimary = AgroColors.Ink950,
    primaryContainer = AgroColors.LimeTint,
    onPrimaryContainer = AgroColors.LimeBright,
    secondary = AgroColors.MintBright,
    onSecondary = AgroColors.Ink950,
    secondaryContainer = AgroColors.MintTint,
    onSecondaryContainer = AgroColors.MintBright,
    surfaceContainer = AgroColors.Ink900,
    background = AgroColors.Ink950,
    onBackground = AgroColors.InkText,
    surface = AgroColors.Ink950,
    onSurface = AgroColors.InkText,
    surfaceVariant = AgroColors.Ink900,
    onSurfaceVariant = AgroColors.InkMuted,
    outline = AgroColors.Ink700,
    outlineVariant = AgroColors.Ink800,
    error = AgroColors.Sev3,
    onError = AgroColors.Ink950,
)

/**
 * Modo exterior: blanco puro contra casi negro.
 *
 * Se sacrifica el refinamiento del banco neutro a cambio del contraste máximo
 * que la pantalla puede dar. Es un intercambio deliberado y solo aplica cuando
 * el sensor de luz detecta condiciones de exterior o el productor lo fija a mano.
 */
private val OutdoorScheme = lightColorScheme(
    primary = AgroColors.BrandOutdoor,
    onPrimary = AgroColors.OutdoorBackground,
    primaryContainer = AgroColors.BrandOutdoor,
    onPrimaryContainer = AgroColors.OutdoorBackground,
    secondary = AgroColors.Bench900,
    onSecondary = AgroColors.OutdoorBackground,
    secondaryContainer = AgroColors.Brand50,
    onSecondaryContainer = AgroColors.BrandOutdoor,
    surfaceContainer = AgroColors.OutdoorBackground,
    background = AgroColors.OutdoorBackground,
    onBackground = AgroColors.OutdoorText,
    surface = AgroColors.OutdoorBackground,
    onSurface = AgroColors.OutdoorText,
    surfaceVariant = AgroColors.Bench100,
    onSurfaceVariant = AgroColors.Bench900,
    outline = AgroColors.Bench700,
    outlineVariant = AgroColors.Bench400,
    error = AgroColors.Sev4Outdoor,
    onError = AgroColors.OutdoorBackground,
)

@Composable
fun AgroTheme(
    appearance: AgroAppearance = if (isSystemInDarkTheme()) AgroAppearance.DARK else AgroAppearance.LIGHT,
    content: @Composable () -> Unit,
) {
    val scheme = when (appearance) {
        AgroAppearance.LIGHT -> LightScheme
        AgroAppearance.DARK -> DarkScheme
        AgroAppearance.OUTDOOR -> OutdoorScheme
    }

    val semantic = when (appearance) {
        AgroAppearance.LIGHT -> LightSemantic

        AgroAppearance.DARK -> LightSemantic.copy(
            severity = SeverityRamp.Dark,
            dataText = AgroColors.InkText,
            mutedText = AgroColors.InkMuted,
            divider = AgroColors.Ink700,
            // Neutro, sin el tinte verde del resto: detrás de un espécimen no
            // va color de marca (§11.1).
            specimenBackdrop = AgroColors.DarkSurfaceElevated,
            heroStart = AgroColors.HeroDarkStart,
            heroEnd = AgroColors.HeroDarkEnd,
            heroContent = AgroColors.InkText,
            heroGlow = AgroColors.LimeBright.copy(alpha = 0.22f),
            heroBorder = AgroColors.Ink700,
            accent = AgroColors.LimeBright,
            onAccent = AgroColors.Ink950,
            card = AgroColors.Ink900,
            sheet = AgroColors.Ink900,
            softLime = AgroColors.LimeTint,
            onSoftLime = AgroColors.LimeBright,
            softSky = AgroColors.SkyTint,
            onSoftSky = AgroColors.SkyBright,
        )

        // En exterior se apagan los pasteles: lo único que importa es el
        // contraste del texto.
        AgroAppearance.OUTDOOR -> LightSemantic.copy(
            severity = SeverityRamp.Outdoor,
            dataText = AgroColors.OutdoorText,
            mutedText = AgroColors.Bench700,
            divider = AgroColors.Bench400,
            heroStart = AgroColors.BrandOutdoor,
            heroEnd = AgroColors.BrandOutdoor,
            card = AgroColors.Bench100,
            softSky = AgroColors.Bench100,
            onSoftSky = AgroColors.OutdoorText,
            softLime = AgroColors.Bench100,
            onSoftLime = AgroColors.OutdoorText,
        )
    }

    val dimensions = when (appearance) {
        // En exterior, bordes más gruesos: la separación por color deja de
        // funcionar cuando el sol lava la pantalla, y la geometría no.
        AgroAppearance.OUTDOOR -> AgroDimensions(borderWidth = 2.dp)
        else -> AgroDimensions()
    }

    CompositionLocalProvider(
        LocalAgroDimensions provides dimensions,
        LocalAgroSemanticColors provides semantic,
        LocalAgroAppearance provides appearance,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AgroTypography,
            shapes = AgroShapes,
            content = content,
        )
    }
}

/**
 * Tres radios, tres jerarquías: controles y etiquetas (pequeño), tarjetas
 * (mediano), piezas protagonistas y hojas inferiores (grande). Un mismo radio en
 * todo aplana la jerarquía.
 */
private val AgroShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

/** Atajos de lectura. Evitan `LocalAgroSemanticColors.current` repartido por
 *  todos los Composables. */
object AgroTheme {
    val dimensions: AgroDimensions
        @Composable @ReadOnlyComposable get() = LocalAgroDimensions.current

    val semantic: AgroSemanticColors
        @Composable @ReadOnlyComposable get() = LocalAgroSemanticColors.current

    val appearance: AgroAppearance
        @Composable @ReadOnlyComposable get() = LocalAgroAppearance.current
}
