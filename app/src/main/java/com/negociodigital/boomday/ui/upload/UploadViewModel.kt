package com.negociodigital.boomday.ui.upload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository
) : ViewModel() {

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private var uploadJob: Job? = null

    /**
     * Llamado por la UI al terminar de grabar (cámara) o al elegir un video de galería.
     */
    fun setRecordedVideo(uri: Uri, durationMs: Long) {
        Timber.d("UploadViewModel: Video listo para revisar: $uri (${durationMs}ms)")
        _uploadState.value = UploadState.Recorded(uri, durationMs)
    }

    /**
     * Descarta el video grabado/importado y vuelve a Idle sin subir nada.
     */
    fun discardRecording() {
        Timber.d("UploadViewModel: Descartando grabación")
        _uploadState.value = UploadState.Idle
    }

    /**
     * Confirma la subida del video actualmente en estado Recorded. No hace nada si el
     * estado no es Recorded (no hay uri que subir).
     */
    fun confirmUpload(title: String, description: String, hashtags: List<String>) {
        val current = _uploadState.value
        if (current !is UploadState.Recorded) {
            Timber.w("UploadViewModel: confirmUpload llamado sin video grabado (estado actual: $current)")
            return
        }
        if (uploadJob?.isActive == true) {
            Timber.w("UploadViewModel: confirmUpload ignorado, ya hay una subida en curso")
            return
        }

        // Transición síncrona: si el usuario hace doble tap antes de la primera emisión
        // real del Flow (hay un round-trip de red de por medio), el estado ya no es
        // Recorded y el guard de arriba, junto con este, evita una segunda subida.
        _uploadState.value = UploadState.Uploading(0)

        uploadJob = viewModelScope.launch {
            uploadRepository.uploadVideoComplete(
                videoUri = current.uri,
                title = title,
                description = description,
                hashtags = hashtags
            ).collect { status ->
                _uploadState.value = when (status) {
                    is UploadStatus.Progress -> UploadState.Uploading(status.percent)
                    // En la emisión final del flow, downloadUrl contiene el videoId de Firestore (ver UploadRepository.uploadVideoComplete)
                    is UploadStatus.Success -> {
                        deleteCachedRecordingIfOwned(current.uri)
                        UploadState.Success(videoId = status.downloadUrl)
                    }
                    is UploadStatus.Error -> UploadState.Error(
                        message = status.throwable.message ?: "Error desconocido al subir el video"
                    )
                    is UploadStatus.Cancelled -> UploadState.Cancelled
                }
            }
        }
    }

    /**
     * Cancela la subida en curso. Al cancelar el Job, se cancela la colección del Flow de
     * StorageRepository.uploadVideoWithProgress, cuyo awaitClose cancela el UploadTask real.
     */
    fun cancelUpload() {
        Timber.d("UploadViewModel: Cancelando subida en curso")
        uploadJob?.cancel()
        uploadJob = null
        _uploadState.value = UploadState.Cancelled
    }

    /**
     * Vuelve a Idle para permitir reintentar tras un error o éxito.
     */
    fun resetState() {
        _uploadState.value = UploadState.Idle
    }

    /**
     * Borra el archivo temporal de la grabación del cache tras una subida exitosa.
     * Solo aplica a videos grabados con CameraX (uri con scheme "file", escritos en
     * cacheDir por CameraCaptureScreen); un video importado de galería usa un content://
     * uri que no nos pertenece y no debe borrarse.
     */
    private fun deleteCachedRecordingIfOwned(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val deleted = File(path).delete()
        Timber.d("UploadViewModel: Limpieza de cache de grabación (deleted=$deleted): $path")
    }
}
