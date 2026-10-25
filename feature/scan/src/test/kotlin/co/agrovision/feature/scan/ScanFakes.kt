package co.agrovision.feature.scan

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.LocationPrecision
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.CatalogCursor
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.IdGenerator
import co.agrovision.core.model.port.ImageQualityInspector
import co.agrovision.core.model.port.ImageStore
import co.agrovision.core.model.port.LocationProvider
import co.agrovision.core.model.port.PestCatalogRepository
import co.agrovision.core.model.port.PestClassifier
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.port.StoredImage
import co.agrovision.core.model.port.TelemetryRecorder
import co.agrovision.core.model.port.UncertaintyGate
import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.QualityAssessment
import co.agrovision.core.model.scan.RawInference
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.settings.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Puertos falsos escritos a mano.
 *
 * **Por qué no MockK aquí.** Los casos de uso exponen `suspend operator fun
 * invoke`, y mockearlos resultó en corrutinas que no completaban: la prueba veía
 * un estado intermedio para siempre, sin excepción ni pista. Diagnosticarlo costó
 * más que escribir esto.
 *
 * Y el resultado es mejor prueba: al construir los casos de uso **reales** sobre
 * estos falsos, se ejercita la lógica de verdad —la cadena de tres compuertas, el
 * orden de persistencia— en vez de la respuesta enlatada de un mock. Si mañana
 * alguien invierte el orden de guardar y mostrar, estas pruebas lo notan; con
 * mocks del caso de uso completo, no.
 */

class FakeQualityInspector(
    var assessment: QualityAssessment = QualityAssessment.Pass(sharpness = 500f, luminance = 128f),
) : ImageQualityInspector {
    override fun inspect(image: RgbImage): QualityAssessment = assessment
}

class FakeUncertaintyGate(
    var decision: GateDecision = GateDecision.InDomain(emptyList(), 0f, 0f),
) : UncertaintyGate {
    override fun evaluate(inference: RawInference): GateDecision = decision
}

class FakeClassifier(
    var version: String? = "2.0.0",
    var inference: RawInference? = null,
) : PestClassifier {
    var warmUpCalls = 0
    var released = false

    override suspend fun warmUp() { warmUpCalls++ }

    override suspend fun classify(image: RgbImage): RawInference =
        inference ?: error("Se pidió una clasificación sin inferencia configurada")

    override fun activeModelVersion(): String? = version

    override fun release() { released = true }
}

class FakeCatalog(private val taxa: List<PestTaxon> = emptyList()) : PestCatalogRepository {
    override fun observeAll(): Flow<List<PestTaxon>> = flowOf(taxa)
    override fun observeById(id: String): Flow<PestTaxon?> = flowOf(taxa.firstOrNull { it.id == id })
    override suspend fun findById(id: String): PestTaxon? = taxa.firstOrNull { it.id == id }
    override suspend fun search(query: String): List<PestTaxon> = taxa
    override suspend fun upsertAll(taxa: List<PestTaxon>) = Unit
    override suspend fun deleteAll(ids: List<String>) = Unit
    override suspend fun syncCursor() = CatalogCursor(null, null)
    override suspend fun updateSyncCursor(cursor: CatalogCursor) = Unit
}

/** Repositorio de capturas en memoria. Registra el orden real de las escrituras,
 *  que es justo lo que una de las pruebas necesita comprobar. */
class FakeCaptureRepository(
    var saveResult: AgroResult<Unit, DomainError> = AgroResult.unit,
) : CaptureRepository {
    val saved = mutableListOf<Capture>()
    val confirmed = mutableListOf<String>()
    val corrected = mutableListOf<Pair<String, String>>()

    override fun observeHistory(): Flow<List<Capture>> = MutableStateFlow(saved.toList())
    override fun observeById(id: String): Flow<Capture?> =
        MutableStateFlow(saved.firstOrNull { it.id == id })
    override fun observePendingCount(): Flow<Int> = MutableStateFlow(0)
    override fun observePendingBytes(): Flow<Long> = MutableStateFlow(0L)

    override suspend fun save(capture: Capture): AgroResult<Unit, DomainError> {
        if (saveResult is AgroResult.Success) saved += capture
        return saveResult
    }

    override suspend fun findById(id: String): Capture? = saved.firstOrNull { it.id == id }
    override suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long) = emptyList<Capture>()
    override suspend fun requeueStuckUploads() = Unit
    override suspend fun skipUnsent() = Unit
    override suspend fun updateSyncState(
        id: String,
        state: SyncState,
        serverId: String?,
        error: String?,
        nextAttemptAtEpochMillis: Long?,
        countsAsAttempt: Boolean,
    ) = Unit

    override suspend fun confirmPrediction(id: String): AgroResult<Unit, DomainError> {
        confirmed += id
        return AgroResult.unit
    }

    override suspend fun correctPrediction(
        id: String,
        classId: String,
    ): AgroResult<Unit, DomainError> {
        corrected += id to classId
        return AgroResult.unit
    }

    override suspend fun purgeExpired(nowEpochMillis: Long): Int = 0
}

class FakeImageStore(
    var result: AgroResult<StoredImage, DomainError> =
        AgroResult.success(StoredImage("/x/img.jpg", "/x/thumb.jpg", 1024L)),
) : ImageStore {
    override suspend fun persist(captureId: String, image: RgbImage) = result
    override suspend fun load(path: String): RgbImage? = null
    override suspend fun delete(paths: List<String>) = Unit
    override suspend fun totalBytes(): Long = 0L
}

class FakeSettings(
    private var preferences: UserPreferences = UserPreferences.DEFAULT.copy(trainingConsent = true),
) : SettingsRepository {
    override fun observe(): Flow<UserPreferences> = flowOf(preferences)
    override suspend fun current(): UserPreferences = preferences
    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        preferences = transform(preferences)
    }
}

class FakeLocationProvider(private val point: GeoPoint? = null) : LocationProvider {
    override suspend fun currentLocation(): GeoPoint? = point
}

class FakeTelemetry : TelemetryRecorder {
    val corrections = mutableListOf<Triple<String, String?, String>>()
    override suspend fun recordScan(
        modelVersion: String,
        outcome: String,
        confidence: Float?,
        latencyMillis: Long,
        delegate: String,
    ) = Unit

    override suspend fun recordCorrection(
        modelVersion: String,
        fromClassId: String?,
        toClassId: String,
    ) {
        corrections += Triple(modelVersion, fromClassId, toClassId)
    }

    override suspend fun recordModelUpdate(
        fromVersion: String?,
        toVersion: String,
        success: Boolean,
    ) = Unit
}

/** Reloj e identificadores fijos: cero pruebas dependientes de la hora real
 *  (§18.1), y un identificador predecible sobre el que aseverar. */
class FixedClock(private val instant: Long = 1_700_000_000_000L) : Clock {
    override fun nowEpochMillis(): Long = instant
}

class SequentialIds(private val prefix: String = "captura-") : IdGenerator {
    private var counter = 0
    override fun newId(): String = "$prefix${++counter}"
}

/** Se ignora en las pruebas de este archivo, pero el caso de uso lo exige. */
val NoLocation = LocationPrecision.VEREDA
