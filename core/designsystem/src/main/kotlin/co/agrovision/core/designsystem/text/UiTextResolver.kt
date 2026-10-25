package co.agrovision.core.designsystem.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import co.agrovision.core.common.MessageKey
import co.agrovision.core.common.UiText
import co.agrovision.core.designsystem.R

/**
 * Resuelve el texto que produjo el dominio contra los recursos de la aplicación.
 *
 * El `when` es exhaustivo a propósito y no tiene rama `else`. Añadir un
 * [MessageKey] deja de compilar hasta que se le da una cadena, lo que impide la
 * situación habitual: una clave nueva que en producción se muestra como
 * «ERROR_UNEXPECTED» porque nadie tradujo el caso.
 */
@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Message -> when (args.size) {
        0 -> stringResource(key.resourceId)
        else -> stringResource(key.resourceId, *args.toTypedArray())
    }
}

@get:StringRes
val MessageKey.resourceId: Int
    get() = when (this) {
        // Compuerta 1 — instrucciones accionables, nunca «imagen inválida».
        MessageKey.QUALITY_TOO_BLURRY -> R.string.calidad_borrosa
        MessageKey.QUALITY_TOO_DARK -> R.string.calidad_oscura
        MessageKey.QUALITY_TOO_BRIGHT -> R.string.calidad_clara
        MessageKey.QUALITY_TOO_FAR -> R.string.calidad_lejos
        MessageKey.QUALITY_NO_PLANT -> R.string.calidad_sin_planta

        // Compuertas 2 y 3
        MessageKey.RESULT_OUT_OF_DOMAIN -> R.string.resultado_no_reconocida
        MessageKey.RESULT_LOW_CONFIDENCE -> R.string.resultado_poca_confianza
        MessageKey.RESULT_AMBIGUOUS -> R.string.resultado_ambiguo

        MessageKey.SCAN_CAMERA_UNAVAILABLE -> R.string.escaneo_sin_camara
        MessageKey.SCAN_MODEL_UNAVAILABLE -> R.string.escaneo_sin_modelo
        MessageKey.SCAN_INFERENCE_FAILED -> R.string.escaneo_fallo_inferencia
        MessageKey.SCAN_CAPTURE_SAVED -> R.string.escaneo_guardado
        MessageKey.SCAN_PREDICTION_CONFIRMED -> R.string.escaneo_confirmado
        MessageKey.SCAN_PREDICTION_CORRECTED -> R.string.escaneo_corregido

        MessageKey.UPLOAD_QUEUED_NEEDS_ACCOUNT -> R.string.cola_necesita_cuenta
        MessageKey.UPLOAD_QUEUED_PENDING_VALIDATION -> R.string.cola_pendiente_validacion
        MessageKey.UPLOAD_QUEUED_SIGN_IN_REQUIRED -> R.string.cola_requiere_sesion
        MessageKey.UPLOAD_QUEUED_OFFLINE -> R.string.cola_sin_conexion
        MessageKey.UPLOAD_COMPLETED -> R.string.cola_completada

        MessageKey.REVIEW_NEEDS_MORE_IMAGES -> R.string.solicitud_faltan_fotos
        MessageKey.REVIEW_CREATED -> R.string.solicitud_creada
        MessageKey.REVIEW_RESOLVED -> R.string.solicitud_resuelta

        MessageKey.ACCOUNT_NOT_SIGNED_IN -> R.string.cuenta_sin_sesion
        MessageKey.ACCOUNT_OTP_TOO_SOON -> R.string.cuenta_otp_muy_pronto
        MessageKey.ACCOUNT_OTP_INVALID -> R.string.cuenta_otp_invalido
        MessageKey.ACCOUNT_PROFILE_INVALID -> R.string.cuenta_perfil_invalido

        MessageKey.MODEL_UPDATE_AVAILABLE -> R.string.modelo_actualizacion_disponible
        MessageKey.MODEL_UPDATE_INSTALLED -> R.string.modelo_actualizado
        MessageKey.MODEL_UPDATE_FAILED_SIGNATURE -> R.string.modelo_firma_invalida
        MessageKey.MODEL_UPDATE_FAILED_INTEGRITY -> R.string.modelo_integridad_invalida
        MessageKey.MODEL_UPDATE_INCOMPATIBLE_APP -> R.string.modelo_app_desactualizada
        MessageKey.MODEL_ROLLED_BACK -> R.string.modelo_revertido

        MessageKey.ERROR_STORAGE_FULL -> R.string.error_sin_espacio
        MessageKey.ERROR_UNEXPECTED -> R.string.error_inesperado
        MessageKey.ERROR_NO_CONNECTION -> R.string.error_sin_conexion
    }
