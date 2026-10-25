package co.agrovision.core.model

import co.agrovision.core.common.MessageKey
import co.agrovision.core.model.identity.ProducerProfile
import co.agrovision.core.model.mlmodel.ModelUpdateFailure

/**
 * Conjunto cerrado de fallos de negocio. Cada caso de uso declara en su firma
 * cuáles puede devolver, de modo que quien llama no descubre en producción que
 * había un camino de error que no contempló.
 */
sealed interface DomainError {

    val message: MessageKey

    // ── Escaneo ──
    data object CameraUnavailable : DomainError {
        override val message = MessageKey.SCAN_CAMERA_UNAVAILABLE
    }

    /** No hay ninguna ranura de modelo activa y válida. Sin modelo no hay
     *  escaneo, y sin escaneo no hay producto. */
    data object ModelUnavailable : DomainError {
        override val message = MessageKey.SCAN_MODEL_UNAVAILABLE
    }

    data class InferenceFailed(val cause: String) : DomainError {
        override val message = MessageKey.SCAN_INFERENCE_FAILED
    }

    /** El modelo predijo una clase que no existe en el catálogo local. Ocurre
     *  cuando el modelo va por delante de la sincronización del catálogo: no es
     *  un fallo del modelo, es un desfase que se resuelve sincronizando. */
    data class UnknownPestClass(val classId: String) : DomainError {
        override val message = MessageKey.ERROR_UNEXPECTED
    }

    // ── Persistencia ──
    data object StorageFull : DomainError {
        override val message = MessageKey.ERROR_STORAGE_FULL
    }

    data class PersistenceFailed(val cause: String) : DomainError {
        override val message = MessageKey.ERROR_UNEXPECTED
    }

    data class NotFound(val entity: String, val id: String) : DomainError {
        override val message = MessageKey.ERROR_UNEXPECTED
    }

    // ── Solicitudes de revisión ──
    data class NotEnoughImages(val provided: Int, val required: Int) : DomainError {
        override val message = MessageKey.REVIEW_NEEDS_MORE_IMAGES
    }

    // ── Cuenta ──

    /** No hay sesión: la operación exige el JWT que entrega el OTP. */
    data object NotSignedIn : DomainError {
        override val message = MessageKey.ACCOUNT_NOT_SIGNED_IN
    }

    /** El servidor solo acepta un código cada 30 s (HTTP 429). */
    data object OtpRequestedTooSoon : DomainError {
        override val message = MessageKey.ACCOUNT_OTP_TOO_SOON
    }

    /** Código OTP incorrecto o vencido. */
    data object InvalidOtp : DomainError {
        override val message = MessageKey.ACCOUNT_OTP_INVALID
    }

    /** Se valida antes de enviar: el backend rechaza el perfil completo por un
     *  solo campo fuera de rango, y sobre 3G ese viaje de ida y vuelta cuesta. */
    data class InvalidProducerProfile(
        val fields: Set<ProducerProfile.Field>,
    ) : DomainError {
        override val message = MessageKey.ACCOUNT_PROFILE_INVALID
    }

    // ── Red y sincronización ──
    data object NoConnection : DomainError {
        override val message = MessageKey.ERROR_NO_CONNECTION
    }

    data class RemoteFailure(val httpCode: Int, val detail: String?) : DomainError {
        override val message = MessageKey.ERROR_UNEXPECTED
    }

    // ── Modelo ──
    data class ModelUpdateRejected(val reason: ModelUpdateFailure) : DomainError {
        override val message = when (reason) {
            ModelUpdateFailure.SIGNATURE_INVALID -> MessageKey.MODEL_UPDATE_FAILED_SIGNATURE
            ModelUpdateFailure.INTEGRITY_MISMATCH -> MessageKey.MODEL_UPDATE_FAILED_INTEGRITY
            ModelUpdateFailure.INCOMPATIBLE_APP_VERSION -> MessageKey.MODEL_UPDATE_INCOMPATIBLE_APP
            ModelUpdateFailure.STORAGE_FULL -> MessageKey.ERROR_STORAGE_FULL
            ModelUpdateFailure.NETWORK -> MessageKey.ERROR_NO_CONNECTION
            ModelUpdateFailure.CALIBRATION_MISSING,
            ModelUpdateFailure.SMOKE_TEST_FAILED,
            -> MessageKey.ERROR_UNEXPECTED
        }
    }

    data class Unexpected(val cause: String) : DomainError {
        override val message = MessageKey.ERROR_UNEXPECTED
    }
}
