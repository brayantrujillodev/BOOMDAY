package com.negociodigital.boomday.ui.upload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.negociodigital.boomday.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Permisos requeridos para grabar (cámara con audio). Importar de galería NO los necesita
 * (usa el Photo Picker moderno, sin permiso runtime).
 */
private val RECORDING_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

/**
 * Puerta de entrada al flujo de captura: decide entre mostrar la cámara directamente
 * (permisos ya concedidos) o pedirlos primero. Los permisos se solicitan aquí, al entrar
 * a esta pantalla, nunca antes.
 *
 * También expone siempre la alternativa "Importar de galería", que no requiere permisos
 * de cámara/audio.
 */
@Composable
internal fun CameraGateScreen(
    onVideoReady: (Uri, Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasRequestedOnce by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var galleryError by remember { mutableStateOf<String?>(null) }
    var isValidatingGallerySelection by remember { mutableStateOf(false) }

    // Re-chequea permisos al volver a primer plano: si el usuario los revocó desde Ajustes
    // mientras la app estaba en background, el estado local (leído solo una vez al entrar
    // a la pantalla) quedaría desactualizado y seguiría mostrando la cámara.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val durationErrorTemplate = stringResource(R.string.upload_gallery_duration_error)
    val readErrorMessage = stringResource(R.string.upload_gallery_read_error)
    val coroutineScope = rememberCoroutineScope()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isValidatingGallerySelection = true
        coroutineScope.launch {
            // MediaMetadataRetriever hace I/O: fuera del hilo principal para no bloquearlo.
            val durationMs = withContext(Dispatchers.IO) { readVideoDurationMsOrNull(context, uri) }
            isValidatingGallerySelection = false
            when {
                durationMs == null -> galleryError = readErrorMessage
                durationMs < UploadLimits.MIN_DURATION_MS || durationMs > UploadLimits.MAX_DURATION_MS -> {
                    galleryError = durationErrorTemplate.format(
                        UploadLimits.MIN_DURATION_SECONDS,
                        UploadLimits.MAX_DURATION_SECONDS,
                        durationMs / 1000
                    )
                }
                else -> {
                    galleryError = null
                    onVideoReady(uri, durationMs)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        cameraGranted = results[Manifest.permission.CAMERA] ?: cameraGranted
        audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: audioGranted
        if (!(cameraGranted && audioGranted)) {
            val canShowRationale = activity != null && (
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                )
            permanentlyDenied = !canShowRationale
        }
    }

    fun requestPermissions() {
        hasRequestedOnce = true
        permissionLauncher.launch(RECORDING_PERMISSIONS)
    }

    fun openGallery() {
        galleryError = null
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    when {
        cameraGranted && audioGranted -> {
            CameraCaptureScreen(
                onVideoRecorded = onVideoReady,
                onImportFromGallery = ::openGallery,
                onClose = onClose,
                galleryError = galleryError,
                isValidatingGallerySelection = isValidatingGallerySelection
            )
        }

        permanentlyDenied -> {
            PermissionBlockedScreen(
                onOpenSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                },
                onImportFromGallery = ::openGallery,
                onClose = onClose,
                galleryError = galleryError,
                isValidatingGallerySelection = isValidatingGallerySelection
            )
        }

        else -> {
            PermissionRequestScreen(
                isRetry = hasRequestedOnce,
                onRequestClick = ::requestPermissions,
                onImportFromGallery = ::openGallery,
                onClose = onClose,
                galleryError = galleryError,
                isValidatingGallerySelection = isValidatingGallerySelection
            )
        }
    }
}

private fun readVideoDurationMsOrNull(context: android.content.Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

@Composable
private fun PermissionRequestScreen(
    isRetry: Boolean,
    onRequestClick: () -> Unit,
    onImportFromGallery: () -> Unit,
    onClose: () -> Unit,
    galleryError: String?,
    isValidatingGallerySelection: Boolean
) {
    val title = stringResource(
        if (isRetry) R.string.upload_permission_rationale_title else R.string.upload_permission_title
    )
    val message = stringResource(
        if (isRetry) R.string.upload_permission_rationale_message else R.string.upload_permission_explanation
    )
    PermissionScaffold(
        title = title,
        message = message,
        primaryButtonLabel = stringResource(R.string.upload_permission_request_button),
        onPrimaryClick = onRequestClick,
        onImportFromGallery = onImportFromGallery,
        onClose = onClose,
        galleryError = galleryError,
        isValidatingGallerySelection = isValidatingGallerySelection
    )
}

@Composable
private fun PermissionBlockedScreen(
    onOpenSettings: () -> Unit,
    onImportFromGallery: () -> Unit,
    onClose: () -> Unit,
    galleryError: String?,
    isValidatingGallerySelection: Boolean
) {
    PermissionScaffold(
        title = stringResource(R.string.upload_permission_denied_title),
        message = stringResource(R.string.upload_permission_denied_message),
        primaryButtonLabel = stringResource(R.string.upload_permission_open_settings_button),
        onPrimaryClick = onOpenSettings,
        onImportFromGallery = onImportFromGallery,
        onClose = onClose,
        galleryError = galleryError,
        isValidatingGallerySelection = isValidatingGallerySelection
    )
}

@Composable
private fun PermissionScaffold(
    title: String,
    message: String,
    primaryButtonLabel: String,
    onPrimaryClick: () -> Unit,
    onImportFromGallery: () -> Unit,
    onClose: () -> Unit,
    galleryError: String?,
    isValidatingGallerySelection: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onPrimaryClick, modifier = Modifier.fillMaxWidth()) {
                Text(primaryButtonLabel)
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onImportFromGallery, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.upload_permission_import_gallery_button))
            }
            if (isValidatingGallerySelection) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            if (galleryError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = galleryError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.upload_close_cd))
        }
    }
}

/**
 * Pantalla de cámara: Preview de CameraX + grabación con VideoCapture/Recorder.
 * Auto-stop al llegar a 60s. Libera la cámara en DisposableEffect(onDispose).
 */
@Composable
private fun CameraCaptureScreen(
    onVideoRecorded: (Uri, Long) -> Unit,
    onImportFromGallery: () -> Unit,
    onClose: () -> Unit,
    galleryError: String?,
    isValidatingGallerySelection: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var recordingError by remember { mutableStateOf<String?>(null) }

    val onVideoRecordedState = rememberUpdatedState(onVideoRecorded)

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var boundCameraProvider: ProcessCameraProvider? = null
        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HIGHEST,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
            videoCapture = capture
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            recording?.stop()
            recording = null
            boundCameraProvider?.unbindAll()
        }
    }

    fun startRecording() {
        val capture = videoCapture ?: return
        if (isRecording) return
        recordingError = null
        val outputFile = File(context.cacheDir, "boomday_rec_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pending = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    elapsedMs = 0L
                }

                is VideoRecordEvent.Status -> {
                    val current = event.recordingStats.recordedDurationNanos / 1_000_000
                    elapsedMs = current
                    if (current >= UploadLimits.MAX_DURATION_MS) {
                        recording?.stop()
                    }
                }

                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recording = null
                    if (!event.hasError()) {
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                        onVideoRecordedState.value(Uri.fromFile(outputFile), durationMs)
                    } else {
                        recordingError = event.cause?.message
                        outputFile.delete()
                    }
                }

                else -> Unit
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = {
                if (isRecording) stopRecording()
                onClose()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.upload_close_cd),
                tint = Color.White
            )
        }

        if (isRecording) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp),
                color = Color.Black.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = stringResource(
                        R.string.upload_camera_timer_format,
                        elapsedMs / 1000,
                        UploadLimits.MAX_DURATION_SECONDS
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (recordingError != null) {
                Text(
                    text = recordingError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            if (galleryError != null) {
                Text(
                    text = galleryError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            if (isValidatingGallerySelection) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
            }

            RecordButton(
                isRecording = isRecording,
                onClick = { if (isRecording) stopRecording() else startRecording() }
            )

            if (!isRecording) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onImportFromGallery) {
                    Text(
                        text = stringResource(R.string.upload_camera_switch_gallery_button),
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Botón de grabar/detener. Se dibuja con formas simples (círculo = grabar, cuadrado
 * redondeado = detener) en vez de íconos: los íconos "Videocam"/"Stop" solo existen en
 * material-icons-extended, y este módulo no depende de esa librería.
 */
@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val cd = stringResource(
        if (isRecording) R.string.upload_camera_record_stop_cd else R.string.upload_camera_record_start_cd
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(color = Color.White.copy(alpha = 0.3f), shape = CircleShape)
            .clickable(onClickLabel = cd, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(color = Color.Red, shape = CircleShape)
            )
        }
    }
}
