package co.agrovision.feature.scan.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.ShutterButton
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.feature.scan.FramingGuidance
import co.agrovision.feature.scan.R
import androidx.compose.foundation.Canvas

/**
 * Vista previa de cámara y captura.
 *
 * **Fotografía única, no análisis continuo del flujo de vídeo.** Es una decisión
 * de producto, no una limitación (§2.1): analizar treinta fotogramas por segundo
 * consume batería, calienta el teléfono y no aporta nada —la plaga no se mueve—.
 * Además impone una disciplina útil, que el productor se acerque y encuadre antes
 * de disparar, y eso es la mitad de la calidad del dataset.
 *
 * El permiso se pide **aquí**, al entrar a la cámara, no al arrancar la app. La
 * primera pantalla es el escaneo y el primer diálogo que ve el productor tiene
 * que tener un motivo evidente delante.
 */
@Composable
fun CameraCapture(
    guidance: FramingGuidance,
    onCapture: (RgbImage) -> Unit,
    modifier: Modifier = Modifier,
    /** Reemplaza la guía de encuadre. La usa la toma guiada de una solicitud,
     *  donde cada foto pide algo distinto (planta entera, envés, detalle…). */
    hint: String? = null,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionRequested) {
            permissionRequested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        // Denegado. No se insiste con otro diálogo: se explica para qué sirve y
        // se deja el control en manos del productor.
        EmptyState(
            modifier = modifier,
            title = stringResource(R.string.camara_sin_permiso_titulo),
            body = stringResource(R.string.camara_sin_permiso_cuerpo),
            icon = Icons.Filled.PhotoCamera,
            action = {
                PrimaryFieldButton(
                    text = stringResource(R.string.camara_permitir),
                    icon = Icons.Filled.PhotoCamera,
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                )
            },
        )
        return
    }

    CameraSurface(guidance = guidance, hint = hint, onCapture = onCapture, modifier = modifier)
}

@Composable
private fun CameraSurface(
    guidance: FramingGuidance,
    hint: String?,
    onCapture: (RgbImage) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var capturing by remember { mutableStateOf(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            // Latencia mínima sobre calidad máxima: el productor está de pie en
            // la ladera y cada segundo de espera es fricción. La compuerta de
            // calidad ya rechaza lo que no sirva.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Vincular al ciclo de vida y, sobre todo, DESVINCULAR al salir. Dejar la
    // cámara abierta mantiene el sensor encendido: batería que el productor
    // necesita para el resto de la jornada.
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            val cameraProvider = provider ?: return@addListener

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            }.onFailure { Log.e(TAG, "No se pudo vincular la cámara", it) }
        }, ContextCompat.getMainExecutor(context))

        onDispose { runCatching { provider?.unbindAll() } }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        FramingGuide(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GuidanceLabel(guidance, hint)

            ShutterButton(
                enabled = !capturing,
                contentDescription = stringResource(R.string.camara_disparar),
                onClick = {
                    capturing = true
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                // `use`: el ImageProxy retiene un buffer del
                                // sensor. Sin cerrarlo, la segunda captura se
                                // bloquea esperando a que se libere.
                                image.use {
                                    it.toRgbImage(AgroConstants.Image.MAX_LONG_SIDE_PX)
                                        ?.let(onCapture)
                                }
                                capturing = false
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Fallo al capturar", exception)
                                capturing = false
                            }
                        },
                    )
                },
            )
        }
    }
}

/**
 * Guía de encuadre: un cuadrado con las cuatro esquinas marcadas.
 *
 * Coincide con el recorte central que hace el preprocesado, así que lo que el
 * productor ve dentro del marco es exactamente lo que va a ver el modelo. Si no
 * coincidieran, estaría encuadrando algo distinto de lo que se analiza.
 */
@Composable
private fun FramingGuide(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height) * FRAME_FRACTION
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val corner = side * CORNER_FRACTION
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)

        listOf(
            // esquina superior izquierda
            Offset(left, top) to listOf(Offset(left + corner, top), Offset(left, top + corner)),
            Offset(left + side, top) to listOf(
                Offset(left + side - corner, top), Offset(left + side, top + corner)
            ),
            Offset(left, top + side) to listOf(
                Offset(left + corner, top + side), Offset(left, top + side - corner)
            ),
            Offset(left + side, top + side) to listOf(
                Offset(left + side - corner, top + side), Offset(left + side, top + side - corner)
            ),
        ).forEach { (origin, ends) ->
            ends.forEach { end ->
                drawLine(Color.White, origin, end, strokeWidth = stroke.width, cap = stroke.cap)
            }
        }

        // Sombreado fuera del marco: dirige la mirada sin tapar la escena.
        drawRect(color = Color.Black.copy(alpha = 0.35f), size = Size(size.width, top))
        drawRect(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(0f, top + side),
            size = Size(size.width, size.height - top - side),
        )
    }
}

@Composable
private fun GuidanceLabel(guidance: FramingGuidance, hint: String?) {
    val message = hint ?: stringResource(
        when (guidance) {
            FramingGuidance.ACERCATE -> R.string.guia_acercate
            FramingGuidance.ALEJATE -> R.string.guia_alejate
            FramingGuidance.MAS_LUZ -> R.string.guia_mas_luz
            FramingGuidance.ESTABILIZA -> R.string.guia_estabiliza
            FramingGuidance.LISTO -> R.string.guia_listo
        }
    )

    Text(
        text = message,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private const val TAG = "AgroVision.Camera"
private const val FRAME_FRACTION = 0.78f
private const val CORNER_FRACTION = 0.12f
