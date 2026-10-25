package co.agrovision.core.model.mlmodel

/**
 * Ranura de modelo. El esquema A/B es lo que elimina por construcción el error
 * prohibido nº 11 (§23).
 *
 * LiteRT mantiene el archivo mapeado en memoria mientras el intérprete existe.
 * Sobrescribirlo en sitio produce un fallo de segmentación en código nativo:
 * imposible de capturar desde Kotlin, se manifiesta como un cierre inesperado
 * sin traza aprovechable. Con dos ranuras y un puntero, nunca se escribe sobre
 * el archivo en uso.
 */
enum class ModelSlot {
    A,
    B;

    val other: ModelSlot get() = if (this == A) B else A

    val directoryName: String get() = "slot_${name.lowercase()}"
}

/** Estado del registro local de modelos. */
data class ModelRegistryState(
    val activeSlot: ModelSlot,
    val active: InstalledModel?,
    val standby: InstalledModel?,
    val lastCheckEpochMillis: Long,
    val lastFailure: ModelUpdateFailure?,
) {
    /** La reversión no requiere descarga: el modelo previo sigue en disco
     *  (§8.4). Por eso el interruptor de emergencia surte efecto en segundos. */
    val canRollBack: Boolean get() = standby != null
}

data class InstalledModel(
    val slot: ModelSlot,
    val version: String,
    val channel: ModelChannel,
    val sha256: String,
    val classCount: Int,
    val embeddingDim: Int,
    val labels: List<ModelLabel>,
    val calibration: CalibrationParams,
    val installedAtEpochMillis: Long,
)

data class ModelLabel(
    val index: Int,
    val classId: String,
    val displayName: String,
    /** Vínculo con el catálogo de plagas. Nulo en la clase de rechazo. */
    val pestId: String?,
) {
    val isRejectClass: Boolean get() = pestId == null
}

/**
 * Los tres artefactos de calibración viajan con el modelo y se versionan con él.
 * Un modelo sin ellos NO puede activarse (§7.2): sin temperatura los logits no
 * son probabilidades interpretables, y sin estadísticas por clase no hay
 * distancia de Mahalanobis que calcular.
 */
data class CalibrationParams(
    /** Temperatura óptima calculada en validación. T > 1 suaviza la
     *  sobreconfianza sistemática de las redes modernas. */
    val temperature: Float,
    /** τ — umbral de probabilidad calibrada máxima (compuerta 3). */
    val confidenceThreshold: Float,
    /** δ — margen mínimo entre la primera y la segunda clase (compuerta 3). */
    val marginThreshold: Float,
    /** Umbral combinado de la compuerta 2, calibrado sobre validación que
     *  incluye deliberadamente muestras fuera de dominio. */
    val oodThreshold: Float,
    /** Peso relativo de la puntuación de energía frente a Mahalanobis. */
    val energyWeight: Float,
    /** Centroide del embedding por clase, indexado igual que los logits. */
    val classCentroids: List<FloatArray>,
    /** Inversa de la matriz de covarianza compartida, aplanada (d×d). */
    val precisionMatrix: FloatArray,
    /** Media y desviación para normalizar la puntuación de energía. */
    val energyMean: Float,
    val energyStdDev: Float,
) {
    val embeddingDim: Int get() = classCentroids.firstOrNull()?.size ?: 0

    /**
     * Modelo de una sola salida (solo clasificación), como el que sale tal cual del
     * entrenamiento: sin embedding no hay centroides ni matriz de precisión, y la
     * compuerta 2 (fuera de dominio) no existe. Solo quedan la temperatura y la
     * compuerta 3 (confianza y margen).
     */
    val isSimple: Boolean get() = classCentroids.isEmpty() && precisionMatrix.isEmpty()

    fun isConsistentWith(classCount: Int): Boolean {
        if (temperature <= 0f) return false
        if (isSimple) return true
        return classCentroids.size == classCount &&
            embeddingDim > 0 &&
            precisionMatrix.size == embeddingDim * embeddingDim
    }
}

/**
 * Canales de despliegue (§8.4). La asignación al canario usa el resto de un hash
 * sobre el `device_id`, nunca un sorteo aleatorio: un dispositivo asignado al
 * canario permanece en él entre consultas, y eso es lo que permite atribuir un
 * cambio en las métricas de campo a la versión del modelo y no al ruido de
 * muestreo.
 */
enum class ModelChannel {
    DRAFT,
    INTERNAL,
    CANARY,
    PRODUCTION,
    DEPRECATED;

    companion object {
        fun parse(raw: String): ModelChannel =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PRODUCTION
    }
}

/** Manifiesto devuelto por el endpoint público de modelos (§8.2). El cliente
 *  nunca decide qué versión le toca: solo obedece. */
data class ModelManifest(
    val version: String,
    val channel: ModelChannel,
    val minAppVersion: String,
    val sizeBytes: Long,
    val sha256: String,
    val signatureBase64: String,
    val modelUrl: String,
    val labelsUrl: String,
    val calibrationUrl: String,
    val releaseNotes: String,
    val killSwitch: Boolean,
)

sealed interface ModelUpdateDecision {
    data object UpToDate : ModelUpdateDecision
    data class Available(val manifest: ModelManifest) : ModelUpdateDecision

    /** El manifiesto exige una versión de aplicación mayor. No es un error: es
     *  la señal de que hay que actualizar desde Play antes de recibir el
     *  modelo nuevo. */
    data class RequiresAppUpdate(val minAppVersion: String) : ModelUpdateDecision

    /** `killSwitch` activo: se revierte a la ranura anterior en el siguiente
     *  arranque, sin descargar nada. */
    data class KillSwitchTriggered(val version: String) : ModelUpdateDecision
}

enum class ModelUpdateFailure {
    NETWORK,
    INTEGRITY_MISMATCH,
    SIGNATURE_INVALID,
    CALIBRATION_MISSING,
    SMOKE_TEST_FAILED,
    STORAGE_FULL,
    INCOMPATIBLE_APP_VERSION,
}
