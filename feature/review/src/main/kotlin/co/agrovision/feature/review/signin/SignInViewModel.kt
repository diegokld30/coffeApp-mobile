package co.agrovision.feature.review.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.UiText
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.identity.CoffeeVariety
import co.agrovision.core.model.identity.PlantingAge
import co.agrovision.core.model.identity.ProducerProfile
import co.agrovision.core.model.port.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInStep { PHONE, CODE, PROFILE }

data class ProfileForm(
    val fullName: String = "",
    val farmName: String = "",
    val vereda: String = "",
    val municipality: String = "",
    val variety: CoffeeVariety? = null,
    val plantingAge: PlantingAge? = null,
    /** Desmarcado por defecto: el consentimiento es explícito (§12.2). */
    val trainingConsent: Boolean = false,
)

/** Campos que el productor tiene que revisar. Incluye variedad y edad, que el
 *  backend no valida porque no los recibe. */
enum class ProfileField { FULL_NAME, FARM_NAME, VEREDA, MUNICIPALITY, VARIETY, PLANTING_AGE }

data class SignInState(
    val step: SignInStep = SignInStep.PHONE,
    val phoneDigits: String = "",
    val phoneInvalid: Boolean = false,
    val code: String = "",
    /** Segundos para poder pedir otro código. El servidor acepta uno cada 30. */
    val resendSeconds: Int = 0,
    val profile: ProfileForm = ProfileForm(),
    val invalidFields: Set<ProfileField> = emptySet(),
    val busy: Boolean = false,
    val error: UiText? = null,
) {
    val formattedPhone: String
        get() = phoneDigits.chunked(3).let { parts ->
            // 300 123 4567: como se dicta un celular en Colombia.
            if (phoneDigits.length == PHONE_DIGITS) {
                "${phoneDigits.take(3)} ${phoneDigits.substring(3, 6)} ${phoneDigits.drop(6)}"
            } else {
                parts.joinToString(" ")
            }
        }
}

sealed interface SignInIntent {
    data class SetPhone(val value: String) : SignInIntent
    data object SendCode : SignInIntent
    data class SetCode(val value: String) : SignInIntent
    data object Verify : SignInIntent
    data object ResendCode : SignInIntent
    data object ChangeNumber : SignInIntent
    data class EditProfile(val transform: (ProfileForm) -> ProfileForm) : SignInIntent
    data object SaveProfile : SignInIntent
    data object Back : SignInIntent
}

sealed interface SignInEffect {
    data object Done : SignInEffect
    data object Closed : SignInEffect
}

/**
 * Ingreso con OTP y perfil de la finca.
 *
 * Solo aparece al enviar una Solicitud de Revisión, nunca al abrir la app: la
 * cuenta se pide cuando hay un motivo evidente delante (§6.4). Si el productor ya
 * tiene sesión y solo le falta el perfil, empieza directamente en ese paso.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val account: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val _effects = Channel<SignInEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            val current = account.current()
            _state.update {
                it.copy(
                    step = if (current.needsSignIn) SignInStep.PHONE else SignInStep.PROFILE,
                    phoneDigits = current.phone?.removePrefix(COUNTRY_CODE).orEmpty(),
                    profile = current.toForm(),
                )
            }
        }
    }

    fun onIntent(intent: SignInIntent) {
        when (intent) {
            is SignInIntent.SetPhone -> _state.update {
                it.copy(phoneDigits = intent.value.filter(Char::isDigit).take(PHONE_DIGITS), phoneInvalid = false)
            }
            SignInIntent.SendCode -> sendCode()
            is SignInIntent.SetCode -> {
                val code = intent.value.filter(Char::isDigit).take(CODE_DIGITS)
                _state.update { it.copy(code = code, error = null) }
                // Al completar los seis números se confirma solo: un toque menos
                // con guantes.
                if (code.length == CODE_DIGITS) verify()
            }
            SignInIntent.Verify -> verify()
            SignInIntent.ResendCode -> if (_state.value.resendSeconds == 0) sendCode()
            SignInIntent.ChangeNumber -> _state.update {
                it.copy(step = SignInStep.PHONE, code = "", error = null)
            }
            is SignInIntent.EditProfile -> _state.update {
                it.copy(profile = intent.transform(it.profile), invalidFields = emptySet(), error = null)
            }
            SignInIntent.SaveProfile -> saveProfile()
            SignInIntent.Back -> when (_state.value.step) {
                SignInStep.CODE -> _state.update { it.copy(step = SignInStep.PHONE, code = "", error = null) }
                else -> viewModelScope.launch { _effects.send(SignInEffect.Closed) }
            }
        }
    }

    private fun sendCode() {
        val digits = _state.value.phoneDigits
        if (digits.length != PHONE_DIGITS) {
            _state.update { it.copy(phoneInvalid = true) }
            return
        }
        runBusy {
            when (val result = account.requestOtp(COUNTRY_CODE + digits)) {
                is AgroResult.Failure -> _state.update { it.copy(error = UiText.Message(result.error.message)) }
                is AgroResult.Success -> {
                    _state.update { it.copy(step = SignInStep.CODE, code = "", error = null) }
                    startCountdown()
                }
            }
        }
    }

    private fun verify() {
        val state = _state.value
        if (state.code.length != CODE_DIGITS || state.busy) return
        runBusy {
            when (val result = account.verifyOtp(COUNTRY_CODE + state.phoneDigits, state.code)) {
                is AgroResult.Failure -> _state.update {
                    it.copy(code = "", error = UiText.Message(result.error.message))
                }
                is AgroResult.Success -> {
                    val signedIn = result.value
                    if (!signedIn.needsProfile) {
                        _effects.send(SignInEffect.Done)
                    } else {
                        // Con perfil ya registrado en el servidor, el formulario
                        // llega relleno y solo falta lo del cafetal.
                        _state.update { it.copy(step = SignInStep.PROFILE, profile = signedIn.toForm(), error = null) }
                    }
                }
            }
        }
    }

    private fun saveProfile() {
        val form = _state.value.profile
        val profile = ProducerProfile(
            fullName = form.fullName,
            farmName = form.farmName,
            vereda = form.vereda,
            municipality = form.municipality,
            trainingConsent = form.trainingConsent,
        )
        val invalid = profile.invalidFields().map {
            when (it) {
                ProducerProfile.Field.FULL_NAME -> ProfileField.FULL_NAME
                ProducerProfile.Field.FARM_NAME -> ProfileField.FARM_NAME
                ProducerProfile.Field.VEREDA -> ProfileField.VEREDA
                ProducerProfile.Field.MUNICIPALITY -> ProfileField.MUNICIPALITY
            }
        }.toSet() +
            setOfNotNull(
                ProfileField.VARIETY.takeIf { form.variety == null },
                ProfileField.PLANTING_AGE.takeIf { form.plantingAge == null },
            )
        if (invalid.isNotEmpty()) {
            _state.update { it.copy(invalidFields = invalid) }
            return
        }

        runBusy {
            when (val result = account.saveProducerProfile(profile)) {
                is AgroResult.Failure -> _state.update { it.copy(error = UiText.Message(result.error.message)) }
                is AgroResult.Success -> {
                    account.updateFarmDetails(checkNotNull(form.variety), checkNotNull(form.plantingAge))
                    _effects.send(SignInEffect.Done)
                }
            }
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (seconds in RESEND_SECONDS downTo 0) {
                _state.update { it.copy(resendSeconds = seconds) }
                if (seconds > 0) delay(ONE_SECOND_MS)
            }
        }
    }

    private fun Account.toForm() = ProfileForm(
        fullName = displayName.orEmpty(),
        farmName = farmName.orEmpty(),
        vereda = vereda.orEmpty(),
        municipality = municipality.orEmpty(),
        variety = coffeeVariety,
        plantingAge = plantingAge,
        trainingConsent = trainingConsent,
    )

    private companion object {
        const val COUNTRY_CODE = "+57"
        const val CODE_DIGITS = 6
        const val RESEND_SECONDS = 30
        const val ONE_SECOND_MS = 1_000L
    }
}

private const val PHONE_DIGITS = 10
