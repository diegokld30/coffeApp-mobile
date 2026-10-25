package co.agrovision.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JWT de acceso del productor.
 *
 * `SharedPreferences` y no DataStore porque lo lee el interceptor de OkHttp, que
 * no es una función suspendida: así no hace falta `runBlocking` en cada petición.
 *
 * El nombre del archivo es el que ya excluyen `backup_rules.xml` y
 * `data_extraction_rules.xml`: el token nunca sale del dispositivo (§15.3).
 *
 * ⚠️ Va en claro dentro del almacenamiento privado de la app. Cifrarlo con
 * `security-crypto` queda pendiente: la versión alfa que admite minSdk 22 falla
 * en el Keystore de algunos fabricantes, y un fallo ahí deja al productor sin
 * poder iniciar sesión.
 */
@Singleton
class SessionTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun accessToken(): String? = preferences.getString(KEY_ACCESS_TOKEN, null)

    fun save(accessToken: String) {
        preferences.edit().putString(KEY_ACCESS_TOKEN, accessToken).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    private companion object {
        const val FILE = "agrovision_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
    }
}
