package com.negociodigital.boomday.ui.upload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository
) : ViewModel() {

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private var uploadJob: Job? = null

    // Grabación pendiente (revisión / subida / error / cancelado) y su id de documento.
    // El id se fija en el primer confirmUpload y se reutiliza en cada reintento del MISMO
    // video, de modo que Firestore recibe siempre set() sobre el mismo documento (sin
    // duplicados). Se limpia al publicar, descartar o cambiar de video.
    private var pendingUri: Uri? = null
    private var pendingVideoId: String? = null

    // Borrador del formulario del ultimo confirmUpload, para prellenar al reintentar.
    private var draftTitle: String = ""
    private var draftDescription: String = ""
    private var draftHashtags: List<String> = emptyList()

    private fun clearDraft() {
        draftTitle = ""
        draftDescription = ""
        draftHashtags = emptyList()
    }

    /**
     * Llamado por la UI al terminar de grabar (cámara), al elegir un video de galería, o
     * al volver a revisión para reintentar tras un error/cancelación (mismo uri).
     */
    fun setRecordedVideo(uri: Uri, durationMs: Long) {
        Timber.d("UploadViewModel: Video listo para revisar: $uri (${durationMs}ms)")
        val previous = pendingUri
        if (previous != null && previous != uri) {
            deleteCachedRecordingIfOwned(previous)
            pendingVideoId = null
            clearDraft()
        }
        pendingUri = uri
        _uploadState.value = UploadState.Recorded(uri, durationMs, draftTitle, draftDescription, draftHashtags)
    }

    /**
     * Descarta el video grabado/importado y vuelve a Idle sin subir nada.
     */
    fun discardRecording() {
        Timber.d("UploadViewModel: Descartando grabación")
        clearPending(deleteFile = true)
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

        val videoId = pendingVideoId ?: UUID.randomUUID().toString().also { pendingVideoId = it }
        draftTitle = title
        draftDescription = description
        draftHashtags = hashtags

        // Transición síncrona: si el usuario hace doble tap antes de la primera emisión
        // real del Flow (hay un round-trip de red de por medio), el estado ya no es
        // Recorded y el guard de arriba, junto con este, evita una segunda subida.
        _uploadState.value = UploadState.Uploading(0)

        uploadJob = viewModelScope.launch {
            try {
                uploadRepository.uploadVideoComplete(
                    videoUri = current.uri,
                    title = title,
                    description = description,
                    hashtags = hashtags,
                    videoId = videoId
                ).collect { status -> handleStatus(status, current.uri) }

                // El flow terminó: si por cualquier motivo no hubo estado terminal, no
                // dejamos la UI en Uploading para siempre.
                if (_uploadState.value is UploadState.Uploading) {
                    Timber.e("UploadViewModel: el flow terminó sin estado terminal")
                    _uploadState.value = UploadState.Error(uploadErrorMessageRes(UploadException.Incomplete()))
                }
            } catch (e: CancellationException) {
                // Cancelación explícita (cancelUpload/onCleared): no es un error. El estado
                // lo fija quien cancela.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "UploadViewModel: Excepción inesperada durante la subida")
                _uploadState.value = UploadState.Error(uploadErrorMessageRes(e))
            }
        }
    }

    private fun handleStatus(status: UploadStatus, uri: Uri) {
        // Un Progress tardio (p. ej. snapshot posterior a cancel()) nunca debe pisar un estado
        // terminal (Error/Cancelled/Success): dejaria la UI en Uploading y luego en error generico.
        if (status is UploadStatus.Progress && _uploadState.value !is UploadState.Uploading) return
        _uploadState.value = when (status) {
            is UploadStatus.Progress -> UploadState.Uploading(status.percent)
            // En la emisión final del flow, downloadUrl contiene el videoId de Firestore (ver UploadRepository.uploadVideoComplete)
            is UploadStatus.Success -> {
                clearPending(deleteFile = true)
                UploadState.Success(videoId = status.downloadUrl)
            }
            // El archivo local se conserva en Error/Cancelled: la UI ofrece "Reintentar"
            // (vuelve a revisión con el mismo uri). Se borra al descartar / resetState.
            is UploadStatus.Error -> UploadState.Error(uploadErrorMessageRes(status.throwable))
            is UploadStatus.Cancelled -> UploadState.Cancelled
        }
    }

    /**
     * Cancela la subida en curso. Al cancelar el Job, se cancela la colección del Flow de
     * StorageRepository.uploadVideoWithProgress, cuyo awaitClose cancela el UploadTask real.
     * Solo actúa mientras se está subiendo: no pisa un estado terminal ya alcanzado.
     */
    fun cancelUpload() {
        if (_uploadState.value !is UploadState.Uploading) {
            Timber.w("UploadViewModel: cancelUpload ignorado, no hay subida en curso")
            return
        }
        Timber.d("UploadViewModel: Cancelando subida en curso")
        uploadJob?.cancel()
        uploadJob = null
        _uploadState.value = UploadState.Cancelled
    }

    /**
     * Vuelve a Idle (tras éxito o al descartar un error/cancelación) y limpia el archivo
     * temporal pendiente. Para reintentar sin perder el video, la UI usa setRecordedVideo.
     */
    fun resetState() {
        if (_uploadState.value is UploadState.Uploading) {
            uploadJob?.cancel()
            uploadJob = null
        }
        clearPending(deleteFile = true)
        _uploadState.value = UploadState.Idle
    }

    override fun onCleared() {
        // viewModelScope ya cancela la subida; solo limpiamos el temporal.
        clearPending(deleteFile = true)
        super.onCleared()
    }

    private fun clearPending(deleteFile: Boolean) {
        val uri = pendingUri
        pendingUri = null
        pendingVideoId = null
        clearDraft()
        if (deleteFile && uri != null) deleteCachedRecordingIfOwned(uri)
    }

    /**
     * Borra el archivo temporal de una grabación de CameraX: solo scheme "file" y solo
     * con el nombre que genera CameraCaptureScreen en cacheDir ("boomday_rec_*.mp4").
     * Un video importado de galería usa content:// y nunca se borra.
     */
    private fun deleteCachedRecordingIfOwned(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val file = File(path)
        if (!file.name.startsWith("boomday_rec_") || file.extension != "mp4") return
        val deleted = file.delete()
        Timber.d("UploadViewModel: Limpieza de cache de grabación (deleted=$deleted): $path")
    }
}
