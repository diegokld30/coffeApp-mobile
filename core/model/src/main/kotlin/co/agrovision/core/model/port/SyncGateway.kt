package co.agrovision.core.model.port

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.review.RemoteReviewStatus
import co.agrovision.core.model.review.ReviewRequest

/**
 * Única frontera de red del dominio.
 *
 * Nótese que ninguna pantalla la conoce. La UI escribe en Room y observa Room;
 * esto lo usa exclusivamente la capa de sincronización, que lee de Room, habla
 * con el servidor y actualiza Room (§9.1).
 */
interface SyncGateway {

    /**
     * Sube un lote de capturas. La API recibe **solo metadatos**: las imágenes
     * van directas al almacenamiento de objetos con URL prefirmada, para que el
     * backend no sea cuello de botella de ancho de banda (ADR-010).
     *
     * Idempotente por el UUID de cada captura: un reintento tras una respuesta
     * perdida no duplica registros.
     */
    suspend fun uploadCaptures(batch: List<Capture>): AgroResult<List<UploadOutcome>, DomainError>

    suspend fun uploadReviewRequests(
        batch: List<ReviewRequest>,
    ): AgroResult<List<UploadOutcome>, DomainError>

    /** Sube el archivo a la URL prefirmada devuelta por el paso anterior. */
    suspend fun uploadImage(
        presignedUrl: String,
        localPath: String,
    ): AgroResult<Unit, DomainError>

    /** Sincronización incremental del catálogo con ETag (§10.3). */
    suspend fun fetchCatalogDelta(
        sinceEpochMillis: Long?,
        etag: String?,
    ): AgroResult<CatalogDelta, DomainError>

    /**
     * Estado en el servidor de las solicitudes del productor, con la resolución
     * del agrónomo cuando la hay. Devuelve estados y no `ReviewRequest` completas
     * porque las imágenes solo existen en el teléfono: el repositorio fusiona el
     * estado sobre la fila local, que queda consultable sin conexión.
     */
    suspend fun fetchMyResolutions(
        sinceEpochMillis: Long?,
    ): AgroResult<List<RemoteReviewStatus>, DomainError>

    suspend fun registerPushToken(token: String): AgroResult<Unit, DomainError>

    /**
     * Telemetría de campo anónima (`public/v1/telemetry/field-metrics`): sin JWT y sin datos
     * del productor. Es lo que llena el tablero de telemetría del panel.
     */
    suspend fun postFieldMetrics(metrics: Map<String, String>): AgroResult<Unit, DomainError>

    /**
     * Manifiesto del modelo que le corresponde a ESTE dispositivo (§8.2).
     *
     * Vive en el puerto y no se llama al servicio HTTP directamente para que la
     * capa de datos no vea tipos de Retrofit. No es purismo: `Response<T>` en la
     * firma obligaría a `:core:data` a depender de Retrofit, y a partir de ahí
     * nada impide que un repositorio empiece a hacer peticiones por su cuenta.
     *
     * Va **sin JWT**. Un usuario anónimo tiene que poder recibir actualizaciones
     * de modelo; exigir autenticación aquí lo congelaría en la versión de fábrica
     * para siempre (§23, error nº 5).
     */
    suspend fun fetchModelManifest(
        appVersion: String,
        deviceId: String,
    ): AgroResult<ModelManifest?, DomainError>
}

data class UploadOutcome(
    val localId: String,
    val serverId: String?,
    val status: UploadStatus,
    /** URL prefirmada para subir la imagen. Vigencia corta y alcance a una sola
     *  clave (§15.3). */
    val presignedImageUrl: String?,
    val rejectionReason: String?,
    /** Solicitudes de revisión: una URL prefirmada POR FOTO, por id de imagen.
     *  Una solicitud tiene varias tomas y cada una va a su propia clave. */
    val imageUploadUrls: Map<String, String> = emptyMap(),
)

enum class UploadStatus {
    ACCEPTED,

    /** El servidor ya tenía este UUID. No es un error: es la idempotencia
     *  funcionando. La fila local se marca como subida. */
    DUPLICATE,

    REJECTED,
}

sealed interface CatalogDelta {
    data object NotModified : CatalogDelta
    data class Changed(
        val upserted: List<PestTaxon>,
        val deletedIds: List<String>,
        val etag: String?,
        val serverTimeEpochMillis: Long,
    ) : CatalogDelta
}
