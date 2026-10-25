package co.agrovision.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.agrovision.feature.review.create.CreateReviewEffect
import co.agrovision.feature.review.create.CreateReviewScreen
import co.agrovision.feature.review.create.CreateReviewViewModel
import co.agrovision.feature.review.create.ReviewSentScreen
import co.agrovision.feature.review.create.ReviewSentViewModel
import co.agrovision.feature.review.list.ReviewListScreen
import co.agrovision.feature.review.list.ReviewListViewModel
import co.agrovision.feature.review.signin.SignInEffect
import co.agrovision.feature.review.signin.SignInScreen
import co.agrovision.feature.review.signin.SignInViewModel
import co.agrovision.feature.scan.FramingGuidance
import co.agrovision.feature.scan.camera.CameraCapture

/**
 * Rutas de Solicitud de Revisión.
 *
 * Viven en `:app` por la misma razón que `ScanRoute`: aquí se resuelve la
 * navegación y aquí se conecta la cámara real con la toma guiada, sin que
 * `:feature:review` dependa de `:feature:scan`.
 */
@Composable
fun CreateReviewRoute(
    onSignIn: (reviewId: String) -> Unit,
    onSent: (reviewId: String) -> Unit,
    onClose: () -> Unit,
) {
    val viewModel: CreateReviewViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CreateReviewEffect.SignIn -> onSignIn(effect.reviewId)
                is CreateReviewEffect.Sent -> onSent(effect.reviewId)
                CreateReviewEffect.Closed -> onClose()
            }
        }
    }

    CreateReviewScreen(
        state = state,
        onIntent = viewModel::onIntent,
        camera = { hint, onCapture, modifier ->
            CameraCapture(
                guidance = FramingGuidance.LISTO,
                hint = hint,
                onCapture = onCapture,
                modifier = modifier,
            )
        },
    )
}

@Composable
fun SignInRoute(onDone: () -> Unit, onClose: () -> Unit) {
    val viewModel: SignInViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SignInEffect.Done -> onDone()
                SignInEffect.Closed -> onClose()
            }
        }
    }

    SignInScreen(state = state, onIntent = viewModel::onIntent)
}

@Composable
fun ReviewSentRoute(
    onSeeRequests: () -> Unit,
    onHome: () -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel: ReviewSentViewModel = hiltViewModel()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    ReviewSentScreen(
        outcome = outcome,
        onSeeRequests = onSeeRequests,
        onHome = onHome,
        onSignIn = onSignIn,
    )
}

@Composable
fun ReviewListRoute(onSignIn: () -> Unit, onAnalyze: () -> Unit) {
    val viewModel: ReviewListViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ReviewListScreen(
        state = state,
        onToggle = viewModel::onToggle,
        onSendNow = viewModel::onSendNow,
        onSignIn = onSignIn,
        onAnalyze = onAnalyze,
    )
}
