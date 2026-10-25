package co.agrovision.core.model.review

import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.scan.Embedding

/**
 * Solicitud de Revisión: el mecanismo que cierra el circuito del producto.
 *
 * Cuando el modelo no reconoce lo que ve, la aplicación no adivina. Genera esto,
 * viaja a la consola, un agrónomo responde y la muestra etiquetada alimenta la
 * siguiente versión del modelo.
 *
 * La segunda apuesta del producto (§1.2): el circuito se cierra o el producto
 * muere. Un productor que envía tres solicitudes sin respuesta deja de
 * enviarlas, y con ello se seca la fuente de datos que hace mejorar el modelo.
 */
data class ReviewRequest(
    val id: String,
    val captureId: String?,
    val images: List<ReviewImage>,
    val cropType: CropType,
    val plantOrgan: PlantOrgan,
    val noteText: String?,
    val noteAudioPath: String?,
    val location: GeoPoint?,
    val createdAtEpochMillis: Long,
    val status: ReviewStatus,
    val resolution: ReviewResolution?,
    val sync: SyncStatus,
    /** Versión del modelo que hizo la lectura. No se guarda: se toma de la captura al
     *  enviar, para que el servidor la conozca aunque esa captura nunca se haya subido. */
    val modelVersion: String? = null,
) {
    val isResolved: Boolean get() = status == ReviewStatus.RESOLVED
}

data class ReviewImage(
    val id: String,
    val imagePath: String,
    val thumbnailPath: String,
    val angle: CaptureAngle,
    /** Permite que el servidor agrupe la solicitud con otras parecidas y que el
     *  agrónomo etiquete el grupo entero (§11.1). */
    val embedding: Embedding?,
)

/**
 * Se piden tomas desde ángulos distintos porque un agrónomo no dictamina sobre
 * una sola fotografía de una lesión foliar: el envés dice cosas que el haz no
 * dice (§5.2).
 */
enum class CaptureAngle {
    GENERAL,
    HAZ,
    ENVES,
    DETALLE,
}

enum class ReviewStatus {
    /** Encolada localmente. El productor recibe confirmación inmediata aunque
     *  no haya señal. */
    PENDING_UPLOAD,
    SUBMITTED,
    ASSIGNED,
    RESOLVED,
    DISCARDED,
}

data class ReviewResolution(
    val type: ResolutionType,
    val resolvedPestId: String?,
    val agronomistResponse: String,
    val resolvedAtEpochMillis: Long,
)

enum class ResolutionType {
    /** El agrónomo confirma lo que el modelo había dicho. */
    CONFIRMED,

    /** Es una plaga que el modelo ya conoce pero no reconoció. Entra al dataset
     *  como caso difícil, con peso incrementado. */
    FALSE_NEGATIVE,

    /** No existe en el catálogo. Se crea el taxón; necesita un mínimo de
     *  muestras antes de incorporarse como clase del modelo. */
    NEW_PEST,

    /** Deficiencia nutricional, daño mecánico, fitotoxicidad, planta sana.
     *  Dato tan valioso como el positivo: sin la clase de rechazo el modelo se
     *  ve obligado a elegir una plaga cuando no hay ninguna. */
    NOT_A_PEST,

    /** Se descarta del dataset, pero se conserva como señal de que la compuerta
     *  de calidad necesita ajuste. */
    UNUSABLE,
}

/**
 * Lo que el servidor sabe de una solicitud (`GET v1/review-requests/mine`).
 *
 * [requestId] es el UUID que generó el teléfono; si el servidor respondiera con
 * su propio id, el repositorio también lo busca por `serverId`.
 */
data class RemoteReviewStatus(
    val requestId: String,
    val status: ReviewStatus,
    val resolution: ReviewResolution?,
    /** Marca de tiempo del SERVIDOR de la última modificación. Solo con ella se
     *  avanza el cursor incremental: el reloj del teléfono no es confiable. */
    val updatedAtEpochMillis: Long?,
)
