package co.agrovision.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * Racimo de hojas de café, plano y grande, dibujado con `Path`.
 *
 * Es la ilustración de marca de la pieza protagonista. Se dibuja en vez de
 * empaquetarse para que escale a cualquier densidad sin pesar nada (§3.2 RNF).
 *
 * Plano a propósito: hojas de un solo color con el nervio central y nada más.
 * El detalle botánico fino se lee como lámina de enciclopedia; las formas
 * grandes y limpias se leen como marca.
 *
 * ⚠️ Es decoración de marca. No va nunca junto a una imagen de espécimen
 * (§11.1).
 */
@Composable
fun LeafCluster(
    leaf: Color,
    leafStrong: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val base = Offset(w * 0.62f, h * 1.02f)

        // De atrás hacia delante: dos hojas translúcidas y una en el acento.
        drawLeaf(base, angle = -128f, length = h * 0.95f, width = h * 0.42f, color = leaf, vein = leafStrong)
        drawLeaf(base, angle = -62f, length = h * 0.88f, width = h * 0.40f, color = leaf, vein = leafStrong)
        drawLeaf(base, angle = -95f, length = h * 1.02f, width = h * 0.44f, color = leafStrong, vein = leaf)

        // Dos cerezas en la base: el guiño que dice «café» y no «planta».
        val r = h * 0.075f
        drawCircle(accent, radius = r, center = Offset(base.x - r * 1.1f, base.y - h * 0.12f))
        drawCircle(accent, radius = r * 0.85f, center = Offset(base.x + r * 1.0f, base.y - h * 0.17f))
    }
}

/** Hoja apuntando en `angle` grados desde `base`. */
private fun DrawScope.drawLeaf(
    base: Offset,
    angle: Float,
    length: Float,
    width: Float,
    color: Color,
    vein: Color,
) {
    rotate(degrees = angle, pivot = base) {
        val half = width / 2f
        val leaf = Path().apply {
            moveTo(base.x, base.y)
            cubicTo(
                base.x + length * 0.20f, base.y - half * 1.30f,
                base.x + length * 0.80f, base.y - half * 1.00f,
                base.x + length, base.y,
            )
            cubicTo(
                base.x + length * 0.80f, base.y + half * 1.00f,
                base.x + length * 0.20f, base.y + half * 1.30f,
                base.x, base.y,
            )
            close()
        }
        drawPath(leaf, color)
        drawLine(
            color = vein,
            start = Offset(base.x + length * 0.08f, base.y),
            end = Offset(base.x + length * 0.86f, base.y),
            strokeWidth = width * 0.045f,
            cap = StrokeCap.Round,
        )
    }
}
