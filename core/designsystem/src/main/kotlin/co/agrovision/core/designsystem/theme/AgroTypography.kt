package co.agrovision.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import co.agrovision.core.designsystem.R

/**
 * Tipografía (§11.3).
 *
 * | Rol | Familia | Por qué |
 * |---|---|---|
 * | Títulos | Plus Jakarta Sans 800 | Formas abiertas y redondas: se lee cercana y actual, no institucional. |
 * | Interfaz | Plus Jakarta Sans 400–700 | Una sola familia en toda la interfaz; la jerarquía la da el peso. |
 * | Datos | IBM Plex Mono 400/500 | Confianzas, versiones, coordenadas. **Cifras tabulares alineadas.** |
 *
 * La monoespaciada para datos no es preferencia estética. Una lista de
 * confianzas —`0.91`, `0.07`, `0.02`— con cifras de ancho variable obliga a leer
 * número por número; con cifras tabulares la columna se escanea de un vistazo.
 *
 * Los archivos viven en `core/designsystem/src/main/res/font/`: estáticos y no
 * variables, porque las fuentes variables no se aplican antes de la API 26 y el
 * parque objetivo empieza en la 22. Licencia SIL OFL 1.1 (Google Fonts).
 */
object AgroFonts {

    /** Plus Jakarta Sans 800, con interletraje cerrado. Solo títulos. */
    val Display: FontFamily = FontFamily(
        Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
        Font(R.font.jakarta_bold, FontWeight.Bold),
    )

    val Sans: FontFamily = FontFamily(
        Font(R.font.jakarta_regular, FontWeight.Normal),
        Font(R.font.jakarta_medium, FontWeight.Medium),
        Font(R.font.jakarta_semibold, FontWeight.SemiBold),
        Font(R.font.jakarta_bold, FontWeight.Bold),
    )

    /** IBM Plex Mono. Sus cifras ya son tabulares. */
    val Mono: FontFamily = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )
}

/**
 * Escala en razón 1.2 desde 14 px base — densa, de herramienta de trabajo.
 *
 * En el móvil se sube el cuerpo a 16 sp respecto a los 14 de la consola web:
 * la consola se usa sentado frente a un monitor, esto se usa de pie en el lote,
 * a pleno sol y con el brazo estirado.
 */
internal object AgroTextSizes {
    val Xs = 12.sp
    val Sm = 14.sp
    val Base = 16.sp
    val Lg = 19.sp
    val Xl = 23.sp
    val Xxl = 28.sp
    val Display = 34.sp
}

private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val AgroTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = AgroFonts.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = AgroTextSizes.Display,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineLarge = TextStyle(
        fontFamily = AgroFonts.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = AgroTextSizes.Xxl,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
        lineHeightStyle = TightLineHeight,
    ),
    headlineMedium = TextStyle(
        fontFamily = AgroFonts.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = AgroTextSizes.Xl,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = TightLineHeight,
    ),
    titleLarge = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = AgroTextSizes.Lg,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Base,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = AgroTextSizes.Base,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = AgroTextSizes.Sm,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = AgroTextSizes.Sm,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AgroFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = AgroTextSizes.Xs,
        lineHeight = 16.sp,
    ),
    // Datos: confianzas, versiones de modelo, coordenadas, identificadores.
    bodySmall = TextStyle(
        fontFamily = AgroFonts.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = AgroTextSizes.Xs,
        lineHeight = 16.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AgroFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = AgroTextSizes.Sm,
        lineHeight = 18.sp,
    ),
)
