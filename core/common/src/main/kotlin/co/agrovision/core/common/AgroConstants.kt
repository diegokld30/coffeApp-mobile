package co.agrovision.core.common

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Valores con significado, en un solo sitio (§3.3: «sin valores mágicos»).
 *
 * Cada constante lleva la razón por la que tiene ese valor y no otro. Un número
 * sin justificación es un número que nadie se atreverá a cambiar dentro de dos
 * años, aunque las condiciones hayan cambiado.
 */
object AgroConstants {

    object Image {
        /** Lado mayor tras redimensionar. Produce entre 200 y 400 KB con calidad
         *  85: suficiente para reentrenar y viable sobre 3G prepago (§9.3). */
        const val MAX_LONG_SIDE_PX = 1280

        /** Calidad JPEG. Por debajo de 85 aparecen artefactos de bloque que el
         *  modelo confunde con textura de lesión. */
        const val JPEG_QUALITY = 85

        /** Miniatura del historial. 256 px permite desplazar la rejilla sin
         *  decodificar imágenes completas. */
        const val THUMBNAIL_PX = 256

        /** Purga de capturas ya sincronizadas y confirmadas. La copia
         *  autoritativa vive en el servidor (§9.3). */
        val RETENTION: Duration = 90.days
    }

    object Model {
        /** Entrada del modelo: 224×224×3 RGB (§7.1). */
        const val INPUT_SIZE_PX = 224
        const val INPUT_CHANNELS = 3

        /** Dimensión del embedding de la penúltima capa. El valor real llega en
         *  `labels.json`; este es el respaldo si el metadato falta. */
        const val DEFAULT_EMBEDDING_DIM = 256

        /**
         * Entre comprobaciones de OTA. La consulta del manifiesto pesa menos de 1 KB: lo que gasta
         * datos es la descarga del modelo, y esa solo ocurre cuando hay una versión nueva. Por eso
         * un modelo corregido llega en minutos y no al día siguiente (y un kill-switch igual).
         */
        val UPDATE_CHECK_INTERVAL: Duration = 15.minutes

        /** Presupuesto de latencia p95 en el dispositivo de referencia
         *  (Snapdragon 680, 4 GB). Por encima, el productor percibe la app como
         *  rota (§3.2 RNF). */
        val LATENCY_BUDGET: Duration = 900.milliseconds

        /** Tiempo máximo tolerable para el precalentamiento del intérprete. */
        val WARMUP_TIMEOUT: Duration = 10.seconds
    }

    object Sync {
        /** Tamaño del lote de subida (§9.4). Diez a veinte capturas por petición:
         *  suficiente para amortizar el establecimiento de conexión, corto para
         *  que un corte no obligue a repetir mucho trabajo. */
        const val BATCH_SIZE = 15

        /** Tras este número de intentos la fila pasa a FAILED y deja de
         *  reintentarse en automático. Sigue visible y reintentable a mano: una
         *  captura nunca desaparece en silencio. */
        const val MAX_ATTEMPTS = 8

        val INITIAL_BACKOFF: Duration = 30.seconds
        val MAX_BACKOFF: Duration = 6.hours

        /** Umbral a partir del cual se ofrece el disparador terciario
         *  (Foreground Service) en lugar de subir en primer plano (§9.4). */
        const val FOREGROUND_SERVICE_THRESHOLD_ITEMS = 25

        val WORKER_PERIOD: Duration = 6.hours
    }

    object Review {
        /** Mínimo de imágenes de una solicitud bien formada. Un agrónomo no
         *  puede dictaminar sobre una sola toma de una lesión foliar (§5.2). */
        const val MIN_IMAGES = 2
        const val MAX_IMAGES = 5

        const val MAX_NOTE_CHARS = 500
    }

    object Location {
        /** Redondeo a nivel de vereda cuando el productor elige precisión
         *  reducida. ~0.01° ≈ 1.1 km en el ecuador (§12.2). */
        const val COARSE_GRID_DEGREES = 0.01

        val FRESHNESS: Duration = 2.minutes
    }

    object Ui {
        /** Objetivo táctil mínimo. 48 dp es el piso de accesibilidad, y aquí
         *  además hay guantes y manos sucias de por medio (§2.1). */
        const val MIN_TOUCH_TARGET_DP = 48

        /** El productor opera con una mano, de pie en el lote: el disparador
         *  necesita ser mucho mayor que el mínimo. */
        const val PRIMARY_ACTION_DP = 96

        const val MOTION_FAST_MS = 120
        const val MOTION_ORCHESTRATED_MS = 220
    }
}
