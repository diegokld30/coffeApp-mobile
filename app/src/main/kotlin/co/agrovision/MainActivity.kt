package co.agrovision

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.agrovision.core.designsystem.theme.AgroAppearance
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.navigation.AgroVisionNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * Única actividad de la aplicación.
 *
 * ⚠️ **No la renombres ni la exportes.** El lanzador publicado es
 * `io.ionic.starter.MainActivity`, y esa continuidad la mantiene el
 * `<activity-alias>` del manifiesto. Ver `PRESERVE.md` §8 y el comentario del
 * manifiesto.
 *
 * ## La regla de producto que gobierna esta clase
 *
 * **Aquí no hay inicio de sesión.** El primer contacto es la cámara, siempre, en
 * el primer arranque y en un dispositivo recién instalado. La cuenta se pide solo
 * al intentar enviar una solicitud de revisión, y con contexto.
 *
 * Un muro de registro al instalar elimina la mayoría de instalaciones antes del
 * primer escaneo: es el error prohibido nº 6 (§23). Si algún día aparece una
 * pantalla de acceso antes de la cámara, algo se rompió.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: si no, se ve un parpadeo del tema por defecto
        // en dispositivos lentos, que son la mayoría del parque objetivo.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            val appearanceViewModel: AppearanceViewModel = hiltViewModel()
            val preferences by appearanceViewModel.preferences.collectAsStateWithLifecycle()

            val appearance = preferences.resolveAppearance()

            // Los iconos de la barra de estado siguen a la apariencia de la
            // app, no a la del sistema: con el tema oscuro forzado y el
            // teléfono en claro, quedarían negros sobre negro.
            DisposableEffect(appearance) {
                val style = if (appearance == AgroAppearance.OUTDOOR) {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                } else {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            AgroTheme(appearance = appearance) {
                AgroVisionNavHost()
            }
        }
    }
}

/**
 * Resuelve la apariencia efectiva.
 *
 * El modo exterior gana sobre el oscuro cuando está fijado: si el productor lo
 * activó, es porque está a pleno sol, y ahí el tema oscuro es exactamente lo
 * contrario de lo que hace falta.
 *
 * Fuera del modo exterior, la app es siempre oscura: el oscuro premium es la
 * identidad de la marca, no una preferencia del sistema. El tema claro sigue
 * definido en `AgroTheme` por si se quiere ofrecer como opción en Ajustes.
 *
 * `AUTO` queda pendiente de conectar al sensor de luz ambiental; mientras tanto
 * se comporta como `NUNCA`.
 */
internal fun co.agrovision.core.model.settings.UserPreferences.resolveAppearance(): AgroAppearance =
    when (outdoorMode) {
        OutdoorMode.SIEMPRE -> AgroAppearance.OUTDOOR
        OutdoorMode.NUNCA, OutdoorMode.AUTO -> AgroAppearance.DARK
    }
