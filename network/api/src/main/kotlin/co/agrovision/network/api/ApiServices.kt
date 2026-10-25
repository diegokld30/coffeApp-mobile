package co.agrovision.network.api

import co.agrovision.network.api.dto.CaptureBatchRequestDto
import co.agrovision.network.api.dto.CatalogDeltaDto
import co.agrovision.network.api.dto.ConsentRevocationDto
import co.agrovision.network.api.dto.ModelManifestDto
import co.agrovision.network.api.dto.OtpRequestDto
import co.agrovision.network.api.dto.OtpVerificationDto
import co.agrovision.network.api.dto.OtpVerificationResponseDto
import co.agrovision.network.api.dto.ProducerProfileRequestDto
import co.agrovision.network.api.dto.ProducerProfileResponseDto
import co.agrovision.network.api.dto.PushTokenDto
import co.agrovision.network.api.dto.ReviewRequestBatchDto
import co.agrovision.network.api.dto.ReviewResolutionDto
import co.agrovision.network.api.dto.UploadBatchResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * **Superficie pública — sin JWT** (§6.2).
 *
 * Que estos tres endpoints no exijan autenticación no es una comodidad: es un
 * requisito. Si el endpoint de modelos exigiera JWT, un usuario anónimo nunca
 * recibiría actualizaciones y quedaría congelado en la versión de fábrica **para
 * siempre** — el error prohibido nº 5 (§23).
 *
 * Y el usuario anónimo no es un caso marginal: es el estado inicial de todo el
 * mundo, porque la app nunca muestra inicio de sesión al abrir por primera vez.
 *
 * La protección de esta superficie es otra: atestación Play Integrity por
 * petición, limitación de tasa por `device_id` e IP, y un rol de base de datos
 * que **no tiene SELECT** sobre datos de usuarios.
 */
interface PublicApiService {

    /**
     * El servidor resuelve qué versión corresponde a ESTE dispositivo. El cliente
     * nunca decide: solo obedece (§8.2). Concentrar el despliegue gradual en un
     * lugar auditable es lo que permite atribuir un cambio en las métricas de
     * campo a la versión del modelo y no al ruido.
     */
    @GET("public/v1/models/current")
    suspend fun currentModel(
        @Query("platform") platform: String = "android",
        @Query("appVersion") appVersion: String,
        @Query("deviceId") deviceId: String,
    ): Response<ModelManifestDto>

    /** Sincronización incremental con ETag: un `304` cuesta una cabecera en vez
     *  de una descarga completa, que sobre datos prepago decide si el catálogo se
     *  actualiza o no. */
    @GET("public/v1/pests/sync")
    suspend fun syncCatalog(
        @Query("since") sinceIso: String?,
        @Header("If-None-Match") etag: String?,
    ): Response<CatalogDeltaDto>

    @POST("public/v1/telemetry/field-metrics")
    suspend fun postFieldMetrics(@Body body: Map<String, String>): Response<Unit>
}

/**
 * **Superficie autenticada** (§6.2). JWT obligatorio, autorización por nivel.
 */
interface AgroVisionApiService {

    /**
     * Ingesta de capturas, idempotente por lote.
     *
     * La API recibe **solo metadatos**. Las imágenes van directas al
     * almacenamiento de objetos por URL prefirmada (ADR-010), para que el backend
     * no sea cuello de botella de ancho de banda.
     */
    @POST("v1/captures/batch")
    suspend fun uploadCaptures(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CaptureBatchRequestDto,
    ): Response<UploadBatchResponseDto>

    @POST("v1/review-requests/batch")
    suspend fun uploadReviewRequests(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReviewRequestBatchDto,
    ): Response<UploadBatchResponseDto>

    /** `since` en milisegundos epoch, hora del servidor. */
    @GET("v1/review-requests/mine")
    suspend fun myResolutions(
        @Query("since") sinceEpochMillis: Long?,
    ): Response<List<ReviewResolutionDto>>

    @POST("v1/devices/push-token")
    suspend fun registerPushToken(@Body body: PushTokenDto): Response<Unit>

    // ── Identidad ──
    // Sin el prefijo `v1/`: así los expone el backend.

    @POST("auth/solicitar-otp")
    suspend fun requestOtp(@Body body: OtpRequestDto): Response<Unit>

    @POST("auth/validar-otp")
    suspend fun verifyOtp(@Body body: OtpVerificationDto): Response<OtpVerificationResponseDto>

    @GET("productores/me")
    suspend fun myProducerProfile(): Response<ProducerProfileResponseDto>

    @POST("productores/me")
    suspend fun saveProducerProfile(@Body body: ProducerProfileRequestDto): Response<Unit>

    @PATCH("productores/me/consentimiento/otorgar")
    suspend fun grantTrainingConsent(): Response<Unit>

    @PATCH("productores/me/consentimiento/revocar")
    suspend fun revokeTrainingConsent(@Body body: ConsentRevocationDto): Response<Unit>
}
