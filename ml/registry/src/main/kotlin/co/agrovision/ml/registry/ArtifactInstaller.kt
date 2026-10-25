package co.agrovision.ml.registry

import android.util.Log
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.InstalledModel
import co.agrovision.core.model.mlmodel.ModelChannel
import co.agrovision.core.model.mlmodel.ModelLabel
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.mlmodel.ModelSlot
import co.agrovision.core.model.mlmodel.ModelUpdateFailure
import co.agrovision.core.model.port.ArtifactFetcher
import co.agrovision.core.model.port.Clock
// La prueba de humo vive en :ml:inference: construir un intérprete es su
// competencia, no la de este módulo. Aquí solo se orquesta el procedimiento.
import co.agrovision.ml.inference.SmokeTestRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instalación de un modelo en la ranura **inactiva** (§8.3).
 *
 * El orden de los siete pasos es el contrato, y cada uno existe por un fallo
 * concreto observado en despliegues de este tipo:
 *
 * 1. **Descarga a `staging/`.** Nunca directamente a una ranura: una descarga
 *    interrumpida —lo normal sobre 3G en ladera— dejaría una ranura a medias que
 *    parece instalada.
 * 2. **SHA-256 calculado EN FLUJO durante la descarga.** No después. Hashear
 *    después obliga a releer 9 MB, y en un dispositivo de 2 GB durante trabajo en
 *    segundo plano eso es memoria que puede no haber.
 * 3. **Comparación de hash.** Discrepancia → descarte inmediato. Detecta
 *    corrupción de transferencia, que es la condición normal de la red objetivo.
 * 4. **Verificación Ed25519 contra la clave pública embebida.** Fallo → descarte
 *    y evento de seguridad. Sin esto, quien controle el CDN ejecuta los pesos que
 *    quiera en toda la flota.
 * 5. **Escritura en la ranura INACTIVA.** La activa permanece intacta y en uso.
 * 6. **Inferencia de humo contra una imagen de referencia embebida.** Detecta
 *    modelos corruptos o incompatibles ANTES de exponerlos al productor.
 * 7. La conmutación del puntero **no ocurre aquí**: la hace el repositorio, y es
 *    el único punto de no retorno.
 *
 * Ningún paso se puede reordenar. En particular, verificar la firma después de
 * mover a la ranura significaría haber escrito ya un artefacto no confiable en
 * disco.
 */
@Singleton
class ArtifactInstaller @Inject constructor(
    private val layout: ModelSlotLayout,
    private val fetcher: ArtifactFetcher,
    private val verifier: Ed25519SignatureVerifier,
    private val smokeTest: SmokeTestRunner,
    private val clock: Clock,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun install(
        manifest: ModelManifest,
        targetSlot: ModelSlot,
        activeSlot: ModelSlot,
        onProgress: (Float) -> Unit = {},
    ): AgroResult<InstalledModel, ModelUpdateFailure> = withContext(io) {

        // Salvaguarda estructural. Si esta condición se diera, produciría un
        // fallo de segmentación nativo imposible de diagnosticar desde Kotlin
        // (§23, error nº 11), así que se comprueba de forma explícita y ruidosa
        // en lugar de confiar en que quien llame lo haga bien.
        check(targetSlot != activeSlot) {
            "Intento de instalar sobre la ranura ACTIVA ($activeSlot). LiteRT la " +
                "mantiene mapeada en memoria: sobrescribirla revienta en código nativo."
        }

        layout.clearStaging()
        val staged = File(layout.stagingDir(), "model.tflite")

        // ── Pasos 1 a 3 ──
        val digest = MessageDigest.getInstance(SHA_256)
        val downloaded = staged.outputStream().use { output ->
            DigestOutputStream(output, digest).use { hashing ->
                fetcher.fetch(manifest.modelUrl, hashing, maxBytes = manifest.sizeBytes) { read, total ->
                    if (total > 0) onProgress(read.toFloat() / total)
                }
            }
        }

        if (downloaded is AgroResult.Failure) {
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.NETWORK)
        }

        val computed = digest.digest().toHexString()
        if (!computed.equals(manifest.sha256, ignoreCase = true)) {
            Log.w(TAG, "Hash no coincide: esperado ${manifest.sha256}, obtenido $computed")
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.INTEGRITY_MISMATCH)
        }

        // ── Artefactos acompañantes ──
        val labelsRaw = fetcher.fetchText(manifest.labelsUrl).valueOrNull()
        val calibrationRaw = fetcher.fetchText(manifest.calibrationUrl).valueOrNull()

        if (labelsRaw == null) {
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.NETWORK)
        }
        // Un modelo sin sus artefactos de calibración NO puede activarse (§7.2):
        // sin temperatura ni estadísticas por clase, las compuertas 2 y 3 no
        // existen, y activarlo sería entregar detección de desconocidos que en
        // realidad no está.
        if (calibrationRaw == null) {
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.CALIBRATION_MISSING)
        }

        val labels = parseLabels(labelsRaw)
        val calibration = parseCalibration(calibrationRaw)
        if (labels == null || calibration == null) {
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.CALIBRATION_MISSING)
        }
        if (!calibration.isConsistentWith(labels.size)) {
            Log.w(TAG, "Calibración incoherente con ${labels.size} clases")
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.CALIBRATION_MISSING)
        }

        // ── Paso 4 ──
        // La firma cubre el hash del CONJUNTO, no solo el .tflite. Firmar solo los
        // pesos dejaría sustituir el calibration.json —y con él los umbrales de
        // las tres compuertas— sin romper la firma.
        val setDigest = MessageDigest.getInstance(SHA_256).apply {
            update(computed.lowercase().toByteArray())
            update(labelsRaw.toByteArray())
            update(calibrationRaw.toByteArray())
        }.digest()

        if (!verifier.verify(setDigest, manifest.signatureBase64)) {
            Log.e(TAG, "FIRMA INVÁLIDA para la versión ${manifest.version}. Se descarta.")
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.SIGNATURE_INVALID)
        }

        // ── Paso 5 ──
        layout.clear(targetSlot)
        val installed = runCatching {
            staged.copyTo(layout.modelFile(targetSlot), overwrite = true)
            layout.labelsFile(targetSlot).writeText(labelsRaw)
            layout.calibrationFile(targetSlot).writeText(calibrationRaw)
        }
        if (installed.isFailure) {
            layout.clear(targetSlot)
            staged.delete()
            return@withContext AgroResult.failure(ModelUpdateFailure.STORAGE_FULL)
        }
        staged.delete()

        // ── Paso 6 ──
        val smokeResult = smokeTest.run(
            modelFile = layout.modelFile(targetSlot),
            expectedClassCount = labels.size,
        )
        if (!smokeResult) {
            Log.e(TAG, "La inferencia de humo falló para ${manifest.version}. Ranura descartada.")
            layout.clear(targetSlot)
            return@withContext AgroResult.failure(ModelUpdateFailure.SMOKE_TEST_FAILED)
        }

        val embeddingDim = calibration.embeddingDim
        layout.manifestFile(targetSlot).writeText(
            json.encodeToString(
                SlotManifestJson.serializer(),
                SlotManifestJson(
                    version = manifest.version,
                    channel = manifest.channel.name,
                    sha256 = computed,
                    classCount = labels.size,
                    embeddingDim = embeddingDim,
                    installedAt = clock.nowEpochMillis(),
                ),
            )
        )

        AgroResult.success(
            InstalledModel(
                slot = targetSlot,
                version = manifest.version,
                channel = manifest.channel,
                sha256 = computed,
                classCount = labels.size,
                embeddingDim = embeddingDim,
                labels = labels,
                calibration = calibration,
                installedAtEpochMillis = clock.nowEpochMillis(),
            )
        )
    }

    /** Reconstruye el estado de una ranura desde disco, sin red. Se usa al
     *  arrancar: el modelo instalado tiene que estar disponible en modo avión. */
    suspend fun readInstalled(slot: ModelSlot): InstalledModel? = withContext(io) {
        if (!layout.isInstalled(slot)) return@withContext null

        runCatching {
            val manifest = json.decodeFromString(
                SlotManifestJson.serializer(),
                layout.manifestFile(slot).readText(),
            )
            val labels = parseLabels(layout.labelsFile(slot).readText()) ?: return@runCatching null
            val calibration = layout.calibrationFile(slot)
                .takeIf { it.isFile }
                ?.let { parseCalibration(it.readText()) }

            InstalledModel(
                slot = slot,
                version = manifest.version,
                channel = ModelChannel.parse(manifest.channel),
                sha256 = manifest.sha256,
                classCount = manifest.classCount,
                embeddingDim = manifest.embeddingDim,
                labels = labels,
                // `!!` justificado: `InstalledModel.calibration` no es nullable y
                // una ranura sin calibración no debería existir salvo en el
                // modelo heredado, que se instala por otra ruta (ADR-014). Si
                // llegara aquí sin ella, es corrupción y hay que verlo.
                calibration = calibration ?: return@runCatching null,
                installedAtEpochMillis = manifest.installedAt,
            )
        }.getOrNull()
    }

    private fun parseLabels(raw: String): List<ModelLabel>? = runCatching {
        val parsed = json.decodeFromString(LabelsJson.serializer(), raw)
        if (!parsed.isWellFormed()) {
            Log.w(TAG, "labels.json malformado: índices con huecos o classId duplicados")
            return null
        }
        parsed.toDomain()
    }.getOrNull()

    private fun parseCalibration(raw: String): CalibrationParams? = runCatching {
        json.decodeFromString(CalibrationJson.serializer(), raw).toDomain()
    }.getOrNull()

    private fun <T, E> AgroResult<T, E>.valueOrNull(): T? =
        (this as? AgroResult.Success)?.value

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TAG = "AgroVision.Registry"
        const val SHA_256 = "SHA-256"
    }
}
