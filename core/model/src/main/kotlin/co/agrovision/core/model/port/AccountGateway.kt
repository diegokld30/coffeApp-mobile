package co.agrovision.core.model.port

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.identity.ProducerProfile

/**
 * Frontera de red de la identidad: OTP y perfil del productor.
 *
 * Separada de [SyncGateway] porque la usa el repositorio de cuenta y no la capa
 * de sincronización. El JWT que devuelve [verifyOtp] lo guarda quien llama; las
 * demás operaciones lo reciben por el interceptor de autenticación.
 */
interface AccountGateway {

    /** El servidor solo acepta un código cada 30 s: el caso devuelve
     *  [DomainError.OtpRequestedTooSoon]. */
    suspend fun requestOtp(phone: String): AgroResult<Unit, DomainError>

    /** Devuelve el JWT de acceso. */
    suspend fun verifyOtp(phone: String, code: String): AgroResult<String, DomainError>

    /** `null` cuando el productor aún no ha registrado su perfil. */
    suspend fun fetchProducerProfile(): AgroResult<ProducerProfile?, DomainError>

    suspend fun saveProducerProfile(
        profile: ProducerProfile,
    ): AgroResult<ProfileRegistration, DomainError>

    suspend fun setTrainingConsent(granted: Boolean): AgroResult<Unit, DomainError>
}

enum class ProfileRegistration {
    CREATED,

    /** El servidor ya tenía un perfil (409). No es un error: el backend no
     *  permite editarlo, así que vale el que está registrado. */
    ALREADY_REGISTERED,
}
