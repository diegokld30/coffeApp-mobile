package co.agrovision.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta de AgroVisión.
 *
 * **La decisión rectora del sistema de diseño (§11.1):** un agrónomo —y también
 * un productor— juzga color, textura y patrón de lesiones foliares. Toda
 * cromaticidad adyacente a esas imágenes contamina la percepción: un panel
 * magenta junto a una hoja desplaza el tono percibido de la hoja.
 *
 * De ahí que la superficie de trabajo sea cromáticamente neutra y que el
 * magenta de marca esté **prohibido en cualquier zona adyacente a imágenes de
 * especímenes**. Vive en navegación, acciones primarias y estados de marca. No
 * es minimalismo por moda: es una restricción funcional derivada del trabajo.
 *
 * Los valores se especifican en OKLCH en el documento de arquitectura porque
 * OKLCH es perceptualmente uniforme: dos colores con la misma L se perciben
 * igual de claros, lo que hace predecible el contraste. Android no entiende
 * OKLCH, así que aquí están ya convertidos a sRGB. Cada línea conserva su origen
 * para que la conversión sea auditable y reproducible.
 *
 * Donde aparece «croma reducido», el color especificado caía fuera de la gama
 * sRGB y se mapeó reduciendo croma a L y H constantes — el mismo procedimiento
 * que CSS Color 4. Mantiene la claridad y el tono, que es lo que sostiene el
 * contraste.
 */
object AgroColors {

    // ── Banco: la superficie de trabajo. Neutra por obligación. ──────────────
    /** oklch(0.985 0.002 250) — fondo del banco */
    val Bench50 = Color(0xFFF9FAFB)

    /** oklch(0.965 0.003 250) — tarjetas y paneles */
    val Bench100 = Color(0xFFF2F4F5)

    /** oklch(0.925 0.004 250) — bordes, separadores, muescas vacías */
    val Bench200 = Color(0xFFE4E6E9)

    /** oklch(0.860 0.005 250) */
    val Bench300 = Color(0xFFCED1D4)

    /** oklch(0.700 0.006 250) — texto deshabilitado, iconografía secundaria */
    val Bench400 = Color(0xFF9C9FA2)

    /** oklch(0.480 0.008 250) — texto secundario */
    val Bench600 = Color(0xFF5A5E62)

    /** oklch(0.360 0.008 250) */
    val Bench700 = Color(0xFF3A3D41)

    /** oklch(0.220 0.008 250) — grafito: texto principal y barra lateral */
    val Bench900 = Color(0xFF181B1E)

    /** oklch(0.150 0.008 250) */
    val Bench950 = Color(0xFF090B0E)

    // ── Marca: verde hoja. Navegación, acción primaria, estado de marca.
    //    NUNCA junto a una imagen de espécimen. ──────────────────────────────
    //
    // Esmeralda saturado, el verde del cafetal recién llovido. El tono de los
    // botones (500) es más oscuro que el del degradado protagonista para que el
    // texto blanco encima pase AA (≥ 4,5:1) en tamaño de cuerpo.

    /** oklch(0.74 0.17 152) — primario en tema oscuro */
    val Brand400 = Color(0xFF34C77B)

    /** oklch(0.53 0.13 155) — acción primaria. Blanco encima: 5,1:1 */
    val Brand500 = Color(0xFF087F4A)

    /** oklch(0.45 0.11 155) */
    val Brand600 = Color(0xFF06663B)

    /** oklch(0.39 0.09 155). Variante de modo exterior: más oscura para
     *  sostener el contraste bajo luz solar directa. */
    val BrandOutdoor = Color(0xFF04522F)

    /** oklch(0.95 0.03 155) — menta: indicador de navegación, fondos suaves. */
    val Brand50 = Color(0xFFE3F6EC)

    // ── Degradado protagonista y lima ────────────────────────────────────────
    //
    // Solo en la pieza principal de cada pantalla. La lima es el acento de la
    // acción más importante: sobre el verde, es lo primero que se ve.

    /** oklch(0.62 0.15 153) — inicio del degradado */
    val HeroStart = Color(0xFF12A15E)

    /** oklch(0.47 0.12 157) — fin del degradado */
    val HeroEnd = Color(0xFF066E40)

    /** oklch(0.92 0.17 122) */
    val Lime = Color(0xFFD4F46C)

    /** oklch(0.26 0.05 155) — tinta sobre lima: 13:1 */
    val LimeInk = Color(0xFF0C2A1A)

    // ── Pasteles de apoyo: fondos de icono y tarjetas de consejo ─────────────
    val LimeSoft = Color(0xFFF1FBD5)
    val Sky = Color(0xFFE4F0FD)
    val SkyInk = Color(0xFF1B5FC7)

    /** Tarjetas sobre fondo blanco: un gris con un pelo de verde. */
    val Card = Color(0xFFF3F6F4)

    // ── Oscuro premium: la apariencia por defecto ────────────────────────────
    //
    // Casi negro con un pelo de verde, no negro puro: el negro puro con texto
    // blanco vibra en pantallas OLED baratas, y el tinte verde amarra la
    // superficie a la marca sin que se note como color.

    /** oklch(0.16 0.012 160) — fondo */
    val Ink950 = Color(0xFF0B110E)

    /** oklch(0.20 0.014 160) — tarjetas */
    val Ink900 = Color(0xFF131B17)

    /** oklch(0.25 0.016 160) — tarjetas elevadas, iconos sobre tarjeta */
    val Ink800 = Color(0xFF1B2520)

    /** oklch(0.31 0.016 160) — bordes finos */
    val Ink700 = Color(0xFF28332D)

    /** Texto principal: 17:1 sobre Ink950. */
    val InkText = Color(0xFFF1F5F2)

    /** Texto secundario: 7,4:1 sobre Ink950. */
    val InkMuted = Color(0xFF9DA8A2)

    /** Lima de la versión oscura: un punto más saturada que la clara. */
    val LimeBright = Color(0xFFC8F45A)

    /** Fondos teñidos de los consejos, sobre Ink950. */
    val LimeTint = Color(0xFF222C17)
    val SkyTint = Color(0xFF15212F)
    val SkyBright = Color(0xFF8EC5FF)
    val MintTint = Color(0xFF142A1E)
    val MintBright = Color(0xFF6FDDA0)

    /** Degradado protagonista en oscuro: verde bosque hacia el fondo. */
    val HeroDarkStart = Color(0xFF1A3A29)
    val HeroDarkEnd = Color(0xFF0F1A14)

    // ── Chispa: alertas de brote. Y nada más. ────────────────────────────────
    /** oklch(0.88 0.17 95) */
    val Spark400 = Color(0xFFFBD530)

    // ── Rampa de severidad ───────────────────────────────────────────────────
    //
    // Va de verde liquen a herrumbre: el color real que toma una hoja de café
    // conforme avanza la roya. Cinco pasos, los mismos que las escalas de
    // severidad usadas en fitopatología del café (§11.2).
    //
    // Que la rampa imite el fenómeno real no es decoración: significa que el
    // productor reconoce el código de color sin que nadie se lo explique.

    /** oklch(0.86 0.05 145) — sano */
    val Sev0 = Color(0xFFBDDABD)

    /** oklch(0.85 0.10 105) — incipiente */
    val Sev1 = Color(0xFFD7D283)

    /** oklch(0.79 0.14 75) — moderado */
    val Sev2 = Color(0xFFEFAC44)

    /** oklch(0.68 0.17 48) — alto */
    val Sev3 = Color(0xFFE97125)

    /** oklch(0.52 0.16 32) — severo */
    val Sev4 = Color(0xFFB23A26)

    // Rampa de modo exterior: mismos tonos, claridad reducida. A pleno sol la
    // pantalla pierde ~40 % de contraste efectivo y la rampa clara se vuelve
    // indistinguible del blanco.
    val Sev0Outdoor = Color(0xFF93C794)
    val Sev1Outdoor = Color(0xFFBFB53B)
    val Sev2Outdoor = Color(0xFFCB8900)
    val Sev3Outdoor = Color(0xFFB95100)
    val Sev4Outdoor = Color(0xFF871300)

    // ── Tema oscuro ──────────────────────────────────────────────────────────
    val DarkSurface = Color(0xFF13161A)
    val DarkSurfaceElevated = Color(0xFF212428)
    val DarkText = Color(0xFFEAEBED)
    val DarkTextMuted = Color(0xFFA2A5A8)

    // ── Modo exterior ────────────────────────────────────────────────────────
    val OutdoorBackground = Color(0xFFFFFFFF)
    val OutdoorText = Color(0xFF040609)

    // ── Semánticos ───────────────────────────────────────────────────────────
    val Error = Color(0xFFB23A26)
    val Success = Color(0xFF2E7D4F)
}

/**
 * Rampa de severidad resuelta según el modo. Se expone como tipo propio y no
 * como cinco colores sueltos porque siempre se usan juntos: la tira de severidad
 * necesita los cinco a la vez, y separarlos abre la puerta a que alguien mezcle
 * un paso del modo exterior con cuatro del normal.
 */
data class SeverityRamp(
    val level0: Color,
    val level1: Color,
    val level2: Color,
    val level3: Color,
    val level4: Color,
    val empty: Color,
) {
    operator fun get(step: Int): Color = when (step.coerceIn(0, 4)) {
        0 -> level0
        1 -> level1
        2 -> level2
        3 -> level3
        else -> level4
    }

    companion object {
        val Standard = SeverityRamp(
            level0 = AgroColors.Sev0,
            level1 = AgroColors.Sev1,
            level2 = AgroColors.Sev2,
            level3 = AgroColors.Sev3,
            level4 = AgroColors.Sev4,
            empty = AgroColors.Bench200,
        )

        val Outdoor = SeverityRamp(
            level0 = AgroColors.Sev0Outdoor,
            level1 = AgroColors.Sev1Outdoor,
            level2 = AgroColors.Sev2Outdoor,
            level3 = AgroColors.Sev3Outdoor,
            level4 = AgroColors.Sev4Outdoor,
            empty = AgroColors.Bench300,
        )

        val Dark = SeverityRamp(
            level0 = AgroColors.Sev0,
            level1 = AgroColors.Sev1,
            level2 = AgroColors.Sev2,
            level3 = AgroColors.Sev3,
            level4 = AgroColors.Sev4,
            empty = AgroColors.Bench700,
        )
    }
}
