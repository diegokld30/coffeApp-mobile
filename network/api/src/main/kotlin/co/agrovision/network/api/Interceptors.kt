package co.agrovision.network.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Superficie pública = cualquier ruta con el segmento `/public/v1/`. Se busca el
 * segmento y no un prefijo porque la base de la API puede llevar su propio
 * prefijo (`/api/` en el backend de desarrollo).
 */
internal fun HttpUrl.isPublicSurface(): Boolean = encodedPath.contains("/public/v1/")

/**
 * Identificador anónimo del dispositivo en cada petición.
 *
 * Sirve para tres cosas y **ninguna** es identificar a una persona: limitación de
 * tasa en la superficie pública, asignación determinística al canal canario y
 * vinculación progresiva de la cuenta.
 *
 * Es un UUID generado localmente, sin relación con identificadores de hardware ni
 * publicitarios (§12.2). Esa propiedad es lo que permite que la superficie
 * pública funcione sin JWT sin convertirse en un rastreador.
 */
@Singleton
class DeviceIdInterceptor @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header(HEADER, deviceIdProvider.deviceId())
                .build()
        )

    private companion object {
        const val HEADER = "X-Device-Id"
    }
}

fun interface DeviceIdProvider {
    fun deviceId(): String
}

/**
 * Atestación Play Integrity en la superficie pública (§15.4).
 *
 * Sin ella, cualquiera puede descargar el manifiesto y los artefactos de modelo
 * en bucle: extracción masiva por clientes no legítimos y factura de CDN.
 *
 * Solo se adjunta bajo `/public/v1`. La superficie autenticada ya tiene JWT, y
 * pedir atestación además duplicaría la latencia de cada escritura —el token de
 * Integrity tarda cientos de milisegundos en generarse.
 *
 * Si el proveedor no está disponible —sin Play Services, dispositivo con ROM
 * alternativa— la petición sale **sin** la cabecera. El servidor decide si la
 * acepta o no; el cliente no se queda sin poder actualizar el modelo por su
 * cuenta, que sería congelar precisamente a los usuarios de gama más baja.
 */
@Singleton
class PlayIntegrityInterceptor @Inject constructor(
    private val tokenProvider: IntegrityTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isPublicSurface()) {
            return chain.proceed(request)
        }

        val token = tokenProvider.tokenOrNull()
            ?: return chain.proceed(request)

        return chain.proceed(request.newBuilder().header(HEADER, token).build())
    }

    private companion object {
        const val HEADER = "X-Integrity-Token"
    }
}

fun interface IntegrityTokenProvider {
    /** `null` cuando no hay Play Services o la atestación falla. */
    fun tokenOrNull(): String?
}

/**
 * JWT de acceso en la superficie autenticada.
 *
 * La renovación del token **no vive aquí**: un interceptor que renueva puede
 * disparar varias renovaciones en paralelo desde un lote de quince capturas, y
 * la detección de reutilización del refresco rotativo (§15.3) las interpretaría
 * como un robo de token y cerraría la sesión del productor. La renovación es
 * responsabilidad de un `Authenticator`, que OkHttp serializa.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: AccessTokenProvider,
    @Named("apiBaseUrl") baseUrl: String,
) : Interceptor {

    private val apiBase: HttpUrl = baseUrl.toHttpUrl()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // El JWT solo viaja a la API. Las fotos van por el mismo cliente a URL
        // prefirmadas del almacenamiento de objetos: filtrar ahí el token del
        // productor sería regalarlo, y S3 además rechaza la petición si trae
        // `Authorization` junto a la firma de la URL.
        if (request.url.isPublicSurface() || !request.url.isSameOrigin(apiBase)) {
            return chain.proceed(request)
        }

        val token = tokenProvider.accessTokenOrNull()
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header(HEADER, "Bearer $token").build()
        )
    }

    private fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private companion object {
        const val HEADER = "Authorization"
    }
}

fun interface AccessTokenProvider {
    fun accessTokenOrNull(): String?
}
