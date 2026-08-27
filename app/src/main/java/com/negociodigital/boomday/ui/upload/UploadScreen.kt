package com.negociodigital.boomday.ui.upload

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Límites de duración de video compartidos por toda la capa de UI de upload.
 *
 * Reflejan (sin importarlos) las constantes reales que ya validan en el backend
 * (ver UploadRepository.MIN_DURATION_SECONDS / MAX_DURATION_SECONDS). Se duplican aquí
 * deliberadamente porque son valores de UX (deshabilitar botones, auto-stop de la
 * grabación, mensaje inmediato al importar de galería) y esta capa no debe depender de
 * data/repository/.
 */
internal object UploadLimits {
    const val MIN_DURATION_SECONDS = 3
    const val MAX_DURATION_SECONDS = 60
    const val MIN_DURATION_MS = MIN_DURATION_SECONDS * 1000L
    const val MAX_DURATION_MS = MAX_DURATION_SECONDS * 1000L

    // Reflejan los mismos límites impuestos en firestore.rules (allow create) — se
    // duplican aquí por la misma razón que los límites de duración: son valores de UX.
    const val MAX_TITLE_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_HASHTAGS = 30
}

/**
 * Punto de entrada del flujo de subida de video (grabar/importar -> revisar -> subir).
 *
 * Pantalla completa fuera del NavBar (ver Routes.UPLOAD_FLOW en NavGraph.kt), como
 * Reels/TikTok. Es una máquina de estados: dado que [UploadState] ya modela cada paso
 * del flujo (Idle, Recorded, Uploading, Success, Error, Cancelled), este composable
 * simplemente despacha a la pantalla correspondiente según el estado del ViewModel,
 * sin necesitar un NavHost anidado.
 *
 * [lastRecorded] es estado local (no del ViewModel) que recuerda el último video
 * grabado/importado para poder "reintentar" desde Error/Cancelled sin volver a grabar:
 * se reconstruye llamando a viewModel.setRecordedVideo() con el mismo uri/duración, que
 * es parte del contrato público del ViewModel (no se modifica ni se agrega nada nuevo).
 *
 * @param onFinish Callback al terminar el flujo (éxito o cierre manual). Navega de
 *   vuelta a MAIN (ver NavGraph.kt).
 */
@Composable
fun UploadFlowScreen(
    onFinish: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val uploadState by viewModel.uploadState.collectAsState()
    var lastRecorded by remember { mutableStateOf<UploadState.Recorded?>(null) }

    LaunchedEffect(uploadState) {
        val current = uploadState
        if (current is UploadState.Recorded) {
            lastRecorded = current
        }
    }

    fun retryToReviewOrReset() {
        val recorded = lastRecorded
        if (recorded != null) {
            // Reconstruye el estado Recorded para volver a la pantalla de revisión con
            // el mismo video, sin pasar de nuevo por cámara/galería.
            viewModel.setRecordedVideo(recorded.uri, recorded.durationMs)
        } else {
            viewModel.resetState()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val state = uploadState) {
            is UploadState.Idle -> {
                CameraGateScreen(
                    onVideoReady = { uri, durationMs -> viewModel.setRecordedVideo(uri, durationMs) },
                    onClose = onFinish
                )
            }

            is UploadState.Recorded -> {
                VideoReviewScreen(
                    recorded = state,
                    onDiscard = { viewModel.discardRecording() },
                    onSubmit = { title, description, hashtags ->
                        viewModel.confirmUpload(title, description, hashtags)
                    },
                    onClose = {
                        viewModel.discardRecording()
                        onFinish()
                    }
                )
            }

            is UploadState.Uploading -> {
                UploadingScreen(
                    progress = state.progress,
                    onCancel = { viewModel.cancelUpload() }
                )
            }

            is UploadState.Success -> {
                UploadSuccessScreen(
                    onFinished = {
                        viewModel.resetState()
                        onFinish()
                    }
                )
            }

            is UploadState.Error -> {
                UploadErrorScreen(
                    message = state.message,
                    canRetryToReview = lastRecorded != null,
                    onRetry = { retryToReviewOrReset() },
                    onDiscard = { viewModel.resetState() }
                )
            }

            is UploadState.Cancelled -> {
                UploadCancelledScreen(
                    canRetryToReview = lastRecorded != null,
                    onRetry = { retryToReviewOrReset() },
                    onDiscard = { viewModel.resetState() }
                )
            }
        }
    }
}
