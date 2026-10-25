package co.agrovision.core.model.port

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import java.io.OutputStream

/**
 * Descarga de artefactos de modelo.
 *
 * Existe como puerto para que `:ml:registry` —donde vive la verificación de hash
 * y de firma— no dependa de ninguna biblioteca de red. La consecuencia práctica
 * es que toda la lógica criptográfica se puede probar en la JVM con artefactos
 * sintéticos, sin servidor y sin emulador. Un verificador que solo se puede
 * probar contra un servidor real es un verificador que en la práctica no se
 * prueba.
 *
 * Se escribe **a un `OutputStream`** en lugar de devolver bytes para que el hash
 * se calcule en flujo durante la descarga (§8.3, paso 3). Cargar 9 MB en memoria
 * para hashearlos después es innecesario, y en un dispositivo de 2 GB durante una
 * descarga en segundo plano, arriesgado.
 */
interface ArtifactFetcher {

    suspend fun fetch(
        url: String,
        sink: OutputStream,
        /** Tamaño que el manifiesto firmado declara: un servidor (o un CDN
         *  comprometido) que mande más no llena el almacenamiento del teléfono. */
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): AgroResult<Long, DomainError>

    /** Para `labels.json` y `calibration.json`, que son pequeños. */
    suspend fun fetchText(url: String): AgroResult<String, DomainError>
}
