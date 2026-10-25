package co.agrovision.feature.review.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import co.agrovision.core.designsystem.component.StatusTone
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.ResolutionType
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewStatus
import co.agrovision.feature.review.R

/**
 * Las cuatro tomas guiadas, en el orden en que se piden: de lo general a lo
 * particular. Primero se aleja y después se acerca, que es como mira un agrónomo
 * en el lote.
 */
val GUIDED_ANGLES = listOf(
    CaptureAngle.GENERAL,
    CaptureAngle.HAZ,
    CaptureAngle.ENVES,
    CaptureAngle.DETALLE,
)

data class AngleGuide(
    val icon: ImageVector,
    val title: Int,
    val body: Int,
    val cameraHint: Int,
    val shortLabel: Int,
)

val CaptureAngle.guide: AngleGuide
    get() = when (this) {
        CaptureAngle.GENERAL -> AngleGuide(
            Icons.Filled.Grass,
            R.string.angulo_general_titulo,
            R.string.angulo_general_cuerpo,
            R.string.angulo_general_camara,
            R.string.angulo_general_corto,
        )
        CaptureAngle.HAZ -> AngleGuide(
            Icons.Filled.Spa,
            R.string.angulo_haz_titulo,
            R.string.angulo_haz_cuerpo,
            R.string.angulo_haz_camara,
            R.string.angulo_haz_corto,
        )
        CaptureAngle.ENVES -> AngleGuide(
            Icons.Filled.Flip,
            R.string.angulo_enves_titulo,
            R.string.angulo_enves_cuerpo,
            R.string.angulo_enves_camara,
            R.string.angulo_enves_corto,
        )
        CaptureAngle.DETALLE -> AngleGuide(
            Icons.Filled.ZoomIn,
            R.string.angulo_detalle_titulo,
            R.string.angulo_detalle_cuerpo,
            R.string.angulo_detalle_camara,
            R.string.angulo_detalle_corto,
        )
    }

@Composable
fun CropType.label(): String = stringResource(
    when (this) {
        CropType.CAFE -> R.string.cultivo_cafe
        CropType.PLATANO -> R.string.cultivo_platano
        CropType.AGUACATE -> R.string.cultivo_aguacate
        CropType.CACAO -> R.string.cultivo_cacao
        CropType.OTRO -> R.string.cultivo_otro
    }
)

@Composable
fun PlantOrgan.label(): String = stringResource(
    when (this) {
        PlantOrgan.HOJA -> R.string.parte_hoja
        PlantOrgan.FRUTO -> R.string.parte_fruto
        PlantOrgan.TALLO -> R.string.parte_tallo
        PlantOrgan.RAIZ -> R.string.parte_raiz
        PlantOrgan.FLOR -> R.string.parte_flor
    }
)

/**
 * Estado que ve el productor. Combina el del servidor con el de la cola local:
 * mientras no haya subido, lo que importa es si está esperando o si falló.
 */
data class DisplayStatus(val text: Int, val tone: StatusTone)

val ReviewRequest.displayStatus: DisplayStatus
    get() = when {
        sync.state == SyncState.FAILED -> DisplayStatus(R.string.estado_fallida, StatusTone.CRITICAL)
        status == ReviewStatus.RESOLVED -> DisplayStatus(R.string.estado_respondida, StatusTone.POSITIVE)
        status == ReviewStatus.ASSIGNED -> DisplayStatus(R.string.estado_asignada, StatusTone.WARNING)
        status == ReviewStatus.DISCARDED -> DisplayStatus(R.string.estado_descartada, StatusTone.CRITICAL)
        sync.state == SyncState.UPLOADED -> DisplayStatus(R.string.estado_enviada, StatusTone.NEUTRAL)
        else -> DisplayStatus(R.string.estado_por_enviar, StatusTone.NEUTRAL)
    }

val ResolutionType.label: Int
    get() = when (this) {
        ResolutionType.CONFIRMED -> R.string.resolucion_confirmada
        ResolutionType.FALSE_NEGATIVE -> R.string.resolucion_falso_negativo
        ResolutionType.NEW_PEST -> R.string.resolucion_plaga_nueva
        ResolutionType.NOT_A_PEST -> R.string.resolucion_no_plaga
        ResolutionType.UNUSABLE -> R.string.resolucion_inutilizable
    }
