package co.agrovision.core.common

/**
 * Texto destinado a la interfaz, producido por capas que no conocen Android.
 *
 * El dominio no puede referenciar un `R.string`, pero tampoco debe devolver
 * cadenas literales: eso deja copia en español repartida por casos de uso, sin
 * posibilidad de revisarla ni de traducirla. La solución es una clave estable;
 * la capa de presentación la resuelve contra `strings.xml`.
 */
sealed interface UiText {

    @JvmInline
    value class Raw(val value: String) : UiText

    data class Message(
        val key: MessageKey,
        val args: List<String> = emptyList(),
    ) : UiText

    companion object {
        fun of(key: MessageKey, vararg args: String) = Message(key, args.toList())
    }
}

/**
 * Catálogo cerrado de mensajes que el dominio puede emitir.
 *
 * Que sea un `enum` y no una cadena tiene una consecuencia práctica: añadir un
 * mensaje obliga a añadir su traducción, porque el `when` exhaustivo del
 * resolvedor deja de compilar hasta que se hace.
 */
enum class MessageKey {
    // ── Compuerta 1: calidad de imagen ──
    QUALITY_TOO_BLURRY,
    QUALITY_TOO_DARK,
    QUALITY_TOO_BRIGHT,
    QUALITY_TOO_FAR,
    QUALITY_NO_PLANT,

    // ── Compuertas 2 y 3: incertidumbre ──
    RESULT_OUT_OF_DOMAIN,
    RESULT_LOW_CONFIDENCE,
    RESULT_AMBIGUOUS,

    // ── Escaneo ──
    SCAN_CAMERA_UNAVAILABLE,
    SCAN_MODEL_UNAVAILABLE,
    SCAN_INFERENCE_FAILED,
    SCAN_CAPTURE_SAVED,
    SCAN_PREDICTION_CONFIRMED,
    SCAN_PREDICTION_CORRECTED,

    // ── Cola de subida ──
    UPLOAD_QUEUED_NEEDS_ACCOUNT,
    UPLOAD_QUEUED_PENDING_VALIDATION,
    UPLOAD_QUEUED_SIGN_IN_REQUIRED,
    UPLOAD_QUEUED_OFFLINE,
    UPLOAD_COMPLETED,

    // ── Solicitudes de revisión ──
    REVIEW_NEEDS_MORE_IMAGES,
    REVIEW_CREATED,
    REVIEW_RESOLVED,

    // ── Cuenta ──
    ACCOUNT_NOT_SIGNED_IN,
    ACCOUNT_OTP_TOO_SOON,
    ACCOUNT_OTP_INVALID,
    ACCOUNT_PROFILE_INVALID,

    // ── Modelo ──
    MODEL_UPDATE_AVAILABLE,
    MODEL_UPDATE_INSTALLED,
    MODEL_UPDATE_FAILED_SIGNATURE,
    MODEL_UPDATE_FAILED_INTEGRITY,
    MODEL_UPDATE_INCOMPATIBLE_APP,
    MODEL_ROLLED_BACK,

    // ── Genéricos ──
    ERROR_STORAGE_FULL,
    ERROR_UNEXPECTED,
    ERROR_NO_CONNECTION,
}
