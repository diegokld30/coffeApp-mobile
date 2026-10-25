package co.agrovision.network.api

import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.identity.ProducerProfile
import co.agrovision.core.model.port.AccountGateway
import co.agrovision.core.model.port.ProfileRegistration
import co.agrovision.network.api.dto.ConsentRevocationDto
import co.agrovision.network.api.dto.OtpRequestDto
import co.agrovision.network.api.dto.OtpVerificationDto
import co.agrovision.network.api.dto.ProducerProfileRequestDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTP y perfil del productor contra `auth/solicitar-otp`, `auth/validar-otp` y
 * `productores/me`.
 *
 * El rol va fijo en `productor`: esta app no la usan agrónomos ni
 * administradores, que operan desde la consola web (§6.1).
 */
@Singleton
class RetrofitAccountGateway @Inject constructor(
    private val api: AgroVisionApiService,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : AccountGateway {

    override suspend fun requestOtp(phone: String): AgroResult<Unit, DomainError> =
        withContext(io) {
            call { api.requestOtp(OtpRequestDto(telefono = phone, rolSeleccionado = ROLE)) }
                .unit()
        }

    override suspend fun verifyOtp(
        phone: String,
        code: String,
    ): AgroResult<String, DomainError> = withContext(io) {
        val result = call {
            api.verifyOtp(
                OtpVerificationDto(telefono = phone, codigo = code, rolSeleccionado = ROLE)
            )
        }
        when (result) {
            // En `validar-otp` un 400/401 es el código, no la sesión.
            is AgroResult.Failure -> {
                val error = result.error
                val wrongCode = error == DomainError.NotSignedIn ||
                    (error is DomainError.RemoteFailure && error.httpCode == BAD_REQUEST)
                if (wrongCode) AgroResult.failure(DomainError.InvalidOtp) else result
            }
            is AgroResult.Success -> {
                val token = result.value.body()?.accessToken
                if (token.isNullOrBlank()) {
                    AgroResult.failure(DomainError.RemoteFailure(result.value.code(), "sin accessToken"))
                } else {
                    AgroResult.success(token)
                }
            }
        }
    }

    override suspend fun fetchProducerProfile(): AgroResult<ProducerProfile?, DomainError> =
        withContext(io) {
            try {
                val response = api.myProducerProfile()
                // Sin perfil registrado todavía: no es un error, es el estado T1.
                if (response.code() == NOT_FOUND) return@withContext AgroResult.success(null)
                if (!response.isSuccessful) return@withContext AgroResult.failure(response.toError())

                val body = response.body()
                val profile = body?.let {
                    ProducerProfile(
                        fullName = it.nombre.orEmpty(),
                        farmName = it.finca.orEmpty(),
                        vereda = it.vereda.orEmpty(),
                        municipality = it.municipio.orEmpty(),
                        trainingConsent = it.consentimiento ?: false,
                    )
                }
                // Un 200 sin datos de finca equivale a no tener perfil.
                AgroResult.success(profile?.takeIf { it.farmName.isNotBlank() })
            } catch (error: IOException) {
                AgroResult.failure(DomainError.NoConnection)
            }
        }

    override suspend fun saveProducerProfile(
        profile: ProducerProfile,
    ): AgroResult<ProfileRegistration, DomainError> = withContext(io) {
        try {
            val response = api.saveProducerProfile(
                ProducerProfileRequestDto(
                    nombre = profile.fullName,
                    finca = profile.farmName,
                    vereda = profile.vereda,
                    municipio = profile.municipality,
                    consentimiento = profile.trainingConsent,
                )
            )
            when {
                response.isSuccessful -> AgroResult.success(ProfileRegistration.CREATED)
                // «Tu perfil de productor ya fue registrado». No hay endpoint
                // para editarlo, así que no es un fallo: vale el registrado.
                response.code() == CONFLICT ->
                    AgroResult.success(ProfileRegistration.ALREADY_REGISTERED)
                else -> AgroResult.failure(response.toError())
            }
        } catch (error: IOException) {
            AgroResult.failure(DomainError.NoConnection)
        }
    }

    override suspend fun setTrainingConsent(granted: Boolean): AgroResult<Unit, DomainError> =
        withContext(io) {
            call {
                if (granted) api.grantTrainingConsent()
                else api.revokeTrainingConsent(ConsentRevocationDto(confirmacion = true))
            }.unit()
        }

    // ── Utilidades ───────────────────────────────────────────────────────────

    /** Devuelve la respuesta entera si fue 2xx, o el error de dominio. */
    private inline fun <T> call(block: () -> Response<T>): AgroResult<Response<T>, DomainError> =
        try {
            val response = block()
            if (response.isSuccessful) AgroResult.success(response)
            else AgroResult.failure(response.toError())
        } catch (error: IOException) {
            AgroResult.failure(DomainError.NoConnection)
        }

    private fun <T> AgroResult<Response<T>, DomainError>.unit(): AgroResult<Unit, DomainError> =
        when (this) {
            is AgroResult.Failure -> this
            is AgroResult.Success -> AgroResult.unit
        }

    private fun Response<*>.toError(): DomainError = when (code()) {
        TOO_MANY_REQUESTS -> DomainError.OtpRequestedTooSoon
        UNAUTHORIZED -> DomainError.NotSignedIn
        else -> DomainError.RemoteFailure(code(), message())
    }

    private companion object {
        const val ROLE = "productor"
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val TOO_MANY_REQUESTS = 429
    }
}
