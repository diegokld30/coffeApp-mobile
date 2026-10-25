package co.agrovision.network.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frontera con la API. Estos tipos **no salen de este módulo**: se mapean al
 * dominio en `RetrofitSyncGateway`.
 *
 * Es una capa anticorrupción, no burocracia. Un cambio de nombre de campo en el
 * backend tiene que romper la compilación de un mapeador, no propagarse en
 * silencio hasta una entidad de Room.
 *
 * `explicitNulls = false` en el `Json` del módulo: enviar `"noteText": null` en
 * cada captura sobre 3G prepago es gastar bytes del productor por nada.
 */

@Serializable
data class ModelManifestDto(
    val version: String,
    val channel: String,
    val minAppVersion: String,
    val sizeBytes: Long,
    val sha256: String,
    val signature: String,
    val artifacts: ArtifactUrlsDto,
    val releaseNotes: String = "",
    val killSwitch: Boolean = false,
)

@Serializable
data class ArtifactUrlsDto(
    val model: String,
    val labels: String,
    val calibration: String,
)

@Serializable
data class CaptureBatchRequestDto(val captures: List<CaptureDto>)

@Serializable
data class CaptureDto(
    val id: String,
    val capturedAt: Long,
    val modelVersion: String,
    val predictedClassId: String? = null,
    val calibratedConfidence: Float? = null,
    val oodScore: Float,
    val gateOutcome: String,
    /** float16 en Base64. La mitad de bytes que float32 y sin pérdida útil. */
    val embedding: String? = null,
    val userCorrectionClassId: String? = null,
    val userConfirmed: Boolean,
    val cropType: String? = null,
    val plantOrgan: String? = null,
    val location: LocationDto? = null,
)

@Serializable
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
)

@Serializable
data class ReviewRequestBatchDto(val requests: List<ReviewRequestDto>)

@Serializable
data class ReviewRequestDto(
    val id: String,
    val captureId: String? = null,
    val cropType: String,
    val plantOrgan: String,
    val noteText: String? = null,
    val createdAt: Long,
    val location: LocationDto? = null,
    val images: List<ReviewImageDto>,
    /** Versión del modelo que hizo la lectura: el servidor la usa si no tiene la captura. */
    val modelVersion: String? = null,
)

@Serializable
data class ReviewImageDto(
    val id: String,
    val angle: String,
    val embedding: String? = null,
)

@Serializable
data class UploadBatchResponseDto(
    val results: List<UploadResultDto>,
    /** Por si el servidor devuelve las URL de fotos a nivel de lote y no dentro
     *  de cada resultado. Los `imageId` son UUID, así que no hay ambigüedad. */
    val imageUploads: List<ImageUploadDto> = emptyList(),
)

@Serializable
data class UploadResultDto(
    val id: String,
    val serverId: String? = null,
    /** `accepted` | `duplicate` | `rejected`. `duplicate` NO es un error: es la
     *  idempotencia funcionando tras una respuesta perdida. */
    val status: String,
    /** Capturas: una URL. En solicitudes de revisión llega `null` y las URL
     *  vienen por foto en [imageUploads]. */
    val uploadUrl: String? = null,
    val reason: String? = null,
    val imageUploads: List<ImageUploadDto> = emptyList(),
)

@Serializable
data class ImageUploadDto(
    val imageId: String,
    val uploadUrl: String? = null,
)

@Serializable
data class CatalogDeltaDto(
    val upserted: List<PestDto> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    val etag: String? = null,
    val serverTime: Long = 0L,
)

@Serializable
data class PestDto(
    val id: String,
    val scientificName: String,
    val commonName: String,
    val localSynonyms: List<String> = emptyList(),
    val crop: String,
    val affectedOrgans: List<String> = emptyList(),
    val description: String = "",
    val recommendations: List<RecommendationDto> = emptyList(),
    val images: List<String> = emptyList(),
    val version: Int = 1,
    /** «Enfermedad», «Plaga», «Deficiencia»… El servidor manda el texto; el
     *  cliente lo normaliza con `PestKind.parse`. */
    val kind: String? = null,
    /** Fuente bibliográfica de la ficha. */
    val reference: String? = null,
    /**
     * Aval profesional (§13.6). Si llega nulo, la ficha se muestra **sin** la
     * sección de manejo: distribuir dosis e ingredientes activos sin agrónomo con
     * tarjeta vigente detrás es exposición ante el ICA, no un detalle de formato.
     */
    val approval: ApprovalDto? = null,
)

@Serializable
data class RecommendationDto(
    val kind: String,
    val summary: String,
    val detail: String = "",
    val activeIngredient: String? = null,
    val dosage: String? = null,
    @SerialName("preHarvestIntervalDays") val preHarvestIntervalDays: Int? = null,
)

@Serializable
data class ApprovalDto(
    val agronomistName: String,
    val licenseNumber: String,
    val approvedAt: Long,
)

@Serializable
data class ReviewResolutionDto(
    val id: String,
    /** `PENDING_UPLOAD` | `SUBMITTED` | `ASSIGNED` | `RESOLVED` | `DISCARDED`. */
    val status: String,
    val resolutionType: String? = null,
    val resolvedPestId: String? = null,
    val agronomistResponse: String? = null,
    val resolvedAt: Long? = null,
    /** Hora del servidor de la última modificación. Si no llega, el cliente no
     *  avanza el cursor y pide la lista completa. */
    val updatedAt: Long? = null,
)

// ── Identidad ────────────────────────────────────────────────────────────────

@Serializable
data class OtpRequestDto(
    val telefono: String,
    val rolSeleccionado: String,
)

@Serializable
data class OtpVerificationDto(
    val telefono: String,
    val codigo: String,
    val rolSeleccionado: String,
)

@Serializable
data class OtpVerificationResponseDto(val accessToken: String)

/** Cuerpo EXACTO de `POST productores/me`: el backend rechaza campos de más. */
@Serializable
data class ProducerProfileRequestDto(
    val nombre: String,
    val finca: String,
    val vereda: String,
    val municipio: String,
    val consentimiento: Boolean,
)

/** Respuesta de `GET productores/me`. Todo opcional: aquí sí puede venir más de
 *  lo que se envió (ids, fechas) y un campo ausente no debe romper el inicio de
 *  sesión. */
@Serializable
data class ProducerProfileResponseDto(
    val nombre: String? = null,
    val finca: String? = null,
    val vereda: String? = null,
    val municipio: String? = null,
    val consentimiento: Boolean? = null,
)

@Serializable
data class PushTokenDto(val token: String, val deviceId: String)

/** `PATCH productores/me/consentimiento/revocar` exige confirmación explícita. */
@Serializable
data class ConsentRevocationDto(val confirmacion: Boolean)
