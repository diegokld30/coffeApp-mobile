package co.agrovision.di

import co.agrovision.BuildConfig
import co.agrovision.core.model.port.AppVersionProvider
import co.agrovision.core.datastore.DeviceIdentityStore
import co.agrovision.core.datastore.SessionTokenStore
import co.agrovision.ml.registry.Ed25519SignatureVerifier
import co.agrovision.network.api.AccessTokenProvider
import co.agrovision.network.api.DeviceIdProvider
import co.agrovision.network.api.IntegrityTokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Named
import javax.inject.Singleton

/**
 * Configuración que solo `:app` conoce.
 *
 * `BuildConfig` se genera por módulo, así que `:network:api` no puede leer la URL
 * base ni los pines de anclaje: los recibe inyectados desde aquí. Es la razón por
 * la que ese módulo declara `@Named("apiBaseUrl")` en vez de leer una constante.
 *
 * Suena a indirección de más y no lo es: permite que `:network:api` se pruebe
 * contra un `MockWebServer` pasándole otra URL, sin tocar `BuildConfig` ni montar
 * el grafo completo de la aplicación.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides
    @Named("apiBaseUrl")
    fun provideApiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Named("pinnedHosts")
    fun providePinnedHosts(): String = BuildConfig.PINNED_HOSTS

    @Provides
    @Named("pins")
    fun providePins(): String = BuildConfig.PINNED_PINS

    /**
     * Registro HTTP solo en depuración.
     *
     * En release devuelve `null` a propósito: el cuerpo de una petición lleva
     * coordenadas del lote y el número de teléfono del productor, y eso no puede
     * acabar en logcat de un dispositivo en campo (§15.3).
     */
    @Provides
    @Singleton
    @Named("loggingInterceptor")
    fun provideLoggingInterceptor(): Interceptor? =
        if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        } else {
            null
        }

    @Provides
    @Singleton
    fun provideAppVersionProvider(): AppVersionProvider =
        AppVersionProvider { BuildConfig.VERSION_NAME }

    @Provides
    @Singleton
    fun provideSignatureVerifier(): Ed25519SignatureVerifier =
        Ed25519SignatureVerifier(BuildConfig.MODEL_SIGNING_PUBLIC_KEY)

    /**
     * Identificador anónimo del dispositivo para las cabeceras HTTP.
     *
     * `runBlocking` sobre DataStore es aceptable **solo aquí**: un interceptor de
     * OkHttp no es una función suspendida y ya corre en un hilo de red, así que
     * bloquearlo no congela nada de la interfaz. La lectura es de un archivo de
     * pocos bytes que además queda en caché tras el primer acceso.
     */
    @Provides
    @Singleton
    fun provideDeviceIdProvider(identity: DeviceIdentityStore): DeviceIdProvider =
        DeviceIdProvider { runBlocking { identity.deviceId() } }

    /**
     * Atestación Play Integrity.
     *
     * ⚠️ Pendiente de implementar contra `com.google.android.play:integrity`.
     * Mientras devuelva `null`, las peticiones a la superficie pública salen sin
     * la cabecera y es el servidor quien decide si las acepta.
     *
     * Devolver `null` es deliberadamente el comportamiento correcto también en
     * producción cuando no hay Play Services: dejar sin actualizaciones de modelo
     * a un dispositivo con ROM alternativa sería congelar justo al segmento de
     * gama más baja.
     */
    @Provides
    @Singleton
    fun provideIntegrityTokenProvider(): IntegrityTokenProvider =
        IntegrityTokenProvider { null }

    /**
     * JWT que dejó el OTP. Sin sesión devuelve `null`: la superficie autenticada
     * responde 401 y la cola espera. Nada se pierde: todo sigue en Room.
     */
    @Provides
    @Singleton
    fun provideAccessTokenProvider(tokens: SessionTokenStore): AccessTokenProvider =
        AccessTokenProvider { tokens.accessToken() }
}
