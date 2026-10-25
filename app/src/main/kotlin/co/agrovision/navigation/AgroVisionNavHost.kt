package co.agrovision.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import co.agrovision.R
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.feature.catalog.CatalogScreen
import co.agrovision.feature.catalog.CatalogViewModel
import co.agrovision.feature.catalog.PestDetailScreen
import co.agrovision.feature.catalog.PestDetailViewModel
import co.agrovision.feature.review.list.ReviewBadgeViewModel
import co.agrovision.home.HomeScreen
import co.agrovision.home.HomeViewModel

/**
 * Grafo de navegación.
 *
 * ## Qué se conserva de §6.4 y qué cambia
 *
 * **No hay inicio de sesión en ninguna parte del arranque.** Esa regla sigue
 * intacta: todo lo alcanzable desde aquí funciona sin cuenta y sin señal. La
 * cuenta se pide solo al intentar enviar una Solicitud de Revisión.
 *
 * Lo que cambia respecto al diseño original es que el destino inicial es Inicio y
 * no la cámara directamente. La razón la dio la primera prueba en dispositivo: si
 * el escaneo no puede responder —sin modelo, sin luz, o porque el productor solo
 * quiere consultar una ficha— abrir en la cámara deja la app sin salida.
 *
 * «Analizar» sigue siendo la acción dominante de la primera pantalla, a un toque.
 */
object AgroVisionDestinations {
    const val HOME = "inicio"
    const val SCAN = "escaneo"
    const val CATALOG = "catalogo"
    const val PEST_DETAIL = "catalogo/{pestId}"
    const val HISTORY = "historial"
    const val REVIEW_LIST = "solicitudes"
    const val REVIEW_CREATE = "solicitudes/nueva/{captureId}"
    const val REVIEW_SENT = "solicitudes/enviada/{reviewId}"
    const val SIGN_IN = "cuenta/ingresar?reviewId={reviewId}"
    const val SETTINGS = "ajustes"

    fun pestDetail(pestId: String) = "catalogo/$pestId"
    fun createReview(captureId: String) = "solicitudes/nueva/$captureId"
    fun reviewSent(reviewId: String) = "solicitudes/enviada/$reviewId"

    /** Con `reviewId`, al terminar sigue a «enviada». Sin él, vuelve atrás. */
    fun signIn(reviewId: String? = null) =
        if (reviewId == null) "cuenta/ingresar" else "cuenta/ingresar?reviewId=$reviewId"
}

private data class BottomDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val BOTTOM_DESTINATIONS = listOf(
    BottomDestination(AgroVisionDestinations.HOME, Icons.Filled.Home, R.string.nav_inicio),
    BottomDestination(
        AgroVisionDestinations.CATALOG,
        Icons.AutoMirrored.Filled.MenuBook,
        R.string.nav_plagas,
    ),
    BottomDestination(
        AgroVisionDestinations.REVIEW_LIST,
        Icons.AutoMirrored.Filled.Chat,
        co.agrovision.feature.review.R.string.nav_solicitudes,
    ),
    BottomDestination(AgroVisionDestinations.HISTORY, Icons.Filled.History, R.string.nav_historial),
)

/**
 * Cambio de pestaña de la barra inferior.
 *
 * Inicio es la raíz del grafo: navegar «hacia» ella con `restoreState` falla en silencio cuando
 * ya está en la pila (por ejemplo tras entrar a Plagas desde un botón de Inicio), y el botón
 * deja de responder. A la raíz se vuelve desapilando, no navegando.
 */
private fun NavHostController.navigateToTab(route: String) {
    if (route == AgroVisionDestinations.HOME) {
        if (!popBackStack(AgroVisionDestinations.HOME, inclusive = false)) {
            navigate(AgroVisionDestinations.HOME) { launchSingleTop = true }
        }
        return
    }

    navigate(route) {
        // Sin esto, alternar entre pestañas apila destinos indefinidamente y el botón
        // atrás recorre todo el historial de pestañeo.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AgroVisionNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    // Respuestas del agrónomo sin abrir. Es lo único que le avisa al productor de
    // que alguien le respondió.
    val badge: ReviewBadgeViewModel = hiltViewModel()
    val unread by badge.unread.collectAsStateWithLifecycle()

    // La barra inferior desaparece en la cámara y en el detalle: son pantallas
    // inmersivas donde una barra roba espacio a lo que el productor está mirando.
    val showBottomBar = BOTTOM_DESTINATIONS.any { destination ->
        currentRoute?.hierarchy?.any { it.route == destination.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // Barra un escalón más clara que el fondo, con el indicador en
                // el tono tenue de marca: la navegación es territorio de marca
                // (§11.1), pero en voz baja.
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    BOTTOM_DESTINATIONS.forEach { destination ->
                        val selected = currentRoute?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(destination.route) },
                            icon = {
                                val count = if (destination.route == AgroVisionDestinations.REVIEW_LIST) unread else 0
                                BadgedBox(
                                    badge = {
                                        if (count > 0) {
                                            Badge(
                                                containerColor = AgroTheme.semantic.accent,
                                                contentColor = AgroTheme.semantic.onAccent,
                                            ) { Text(count.toString()) }
                                        }
                                    },
                                ) {
                                    Icon(destination.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = AgroTheme.semantic.mutedText,
                                unselectedTextColor = AgroTheme.semantic.mutedText,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AgroVisionDestinations.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(AgroVisionDestinations.HOME) {
                val viewModel: HomeViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                HomeScreen(
                    catalogCount = state.catalogCount,
                    featured = state.featured,
                    onAnalyze = { navController.navigate(AgroVisionDestinations.SCAN) },
                    onCatalog = { navController.navigateToTab(AgroVisionDestinations.CATALOG) },
                    onPestClick = {
                        navController.navigate(AgroVisionDestinations.pestDetail(it))
                    },
                )
            }

            composable(AgroVisionDestinations.SCAN) {
                ScanRoute(
                    onPestDetail = { navController.navigate(AgroVisionDestinations.pestDetail(it)) },
                    onReviewRequest = { navController.navigate(AgroVisionDestinations.createReview(it)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = AgroVisionDestinations.REVIEW_CREATE,
                arguments = listOf(navArgument("captureId") { type = NavType.StringType }),
            ) {
                // Enviada o pendiente de ingreso, la solicitud ya está guardada:
                // atrás no vuelve ni al formulario ni a la cámara.
                CreateReviewRoute(
                    onSignIn = { id ->
                        navController.navigate(AgroVisionDestinations.signIn(id)) {
                            popUpTo(AgroVisionDestinations.HOME)
                        }
                    },
                    onSent = { id ->
                        navController.navigate(AgroVisionDestinations.reviewSent(id)) {
                            popUpTo(AgroVisionDestinations.HOME)
                        }
                    },
                    onClose = { navController.popBackStack() },
                )
            }

            composable(
                route = AgroVisionDestinations.SIGN_IN,
                arguments = listOf(
                    navArgument("reviewId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val reviewId = entry.arguments?.getString("reviewId")
                SignInRoute(
                    onDone = {
                        if (reviewId != null) {
                            navController.navigate(AgroVisionDestinations.reviewSent(reviewId)) {
                                popUpTo(AgroVisionDestinations.HOME)
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onClose = { navController.popBackStack() },
                )
            }

            composable(
                route = AgroVisionDestinations.REVIEW_SENT,
                arguments = listOf(navArgument("reviewId") { type = NavType.StringType }),
            ) { entry ->
                val reviewId = checkNotNull(entry.arguments?.getString("reviewId"))
                ReviewSentRoute(
                    onSeeRequests = {
                        navController.navigate(AgroVisionDestinations.REVIEW_LIST) {
                            popUpTo(AgroVisionDestinations.HOME)
                            launchSingleTop = true
                        }
                    },
                    onHome = { navController.popBackStack(AgroVisionDestinations.HOME, inclusive = false) },
                    onSignIn = { navController.navigate(AgroVisionDestinations.signIn(reviewId)) },
                )
            }

            composable(AgroVisionDestinations.REVIEW_LIST) {
                ReviewListRoute(
                    onSignIn = { navController.navigate(AgroVisionDestinations.signIn()) },
                    onAnalyze = { navController.navigate(AgroVisionDestinations.SCAN) },
                )
            }

            composable(AgroVisionDestinations.CATALOG) {
                val viewModel: CatalogViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                CatalogScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onKindChange = viewModel::onKindChange,
                    onPestClick = {
                        navController.navigate(AgroVisionDestinations.pestDetail(it))
                    },
                )
            }

            composable(
                route = AgroVisionDestinations.PEST_DETAIL,
                arguments = listOf(navArgument("pestId") { type = NavType.StringType }),
            ) {
                val viewModel: PestDetailViewModel = hiltViewModel()
                val pest by viewModel.pest.collectAsStateWithLifecycle()

                PestDetailScreen(pest = pest, onBack = { navController.popBackStack() })
            }

            composable(AgroVisionDestinations.HISTORY) {
                // ⚠️ Pendiente. Se declara la ruta para que la barra inferior
                // tenga tres destinos reales desde ya; la pantalla llega después.
                HistoryPlaceholder()
            }
        }
    }
}
