package com.negociodigital.boomday.data.repository

import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_VIDEO_CONTENT_TYPE = "video/mp4"
private const val THUMBNAIL_CONTENT_TYPE = "image/jpeg"

/**
 * storage.rules exige request.resource.contentType de tipo video (o image en thumbnails); sin
 * metadata explicita putFile/putBytes no envian contentType y la regla deniega (403).
 * Usa el tipo resuelto (p. ej. ContentResolver.getType) solo si empieza por "video/"; si no, video/mp4
 * (CameraX graba MP4).
 */
internal fun videoContentTypeFor(resolvedType: String?): String =
    if (resolvedType != null && resolvedType.startsWith("video/", ignoreCase = true)) resolvedType
    else DEFAULT_VIDEO_CONTENT_TYPE

internal fun videoMetadata(resolvedType: String?): StorageMetadata =
    StorageMetadata.Builder().setContentType(videoContentTypeFor(resolvedType)).build()

internal fun thumbnailMetadata(): StorageMetadata =
    StorageMetadata.Builder().setContentType(THUMBNAIL_CONTENT_TYPE).build()

/** Sin avance de bytesTransferred durante este tiempo la subida se considera atascada (sin red). */
internal const val DEFAULT_STALL_TIMEOUT_MS = 45_000L

/**
 * Watchdog de INACTIVIDAD de progreso (no timeout global). Mantiene el maximo de bytes
 * transferidos; el contador solo se reinicia cuando ese maximo AUMENTA, de modo que eventos
 * repetidos con los mismos bytes (o 0 bytes desde el inicio) no lo reinician. Usa delay() en
 * [scope], asi que funciona con tiempo virtual de kotlinx-coroutines-test.
 */
internal class StallWatchdog(
    private val scope: CoroutineScope,
    private val stallTimeoutMs: Long,
    private val onStall: () -> Unit
) {
    private var maxBytes = 0L
    private var job: Job? = null
    private var stopped = false

    @Synchronized
    fun start() {
        if (stopped) return
        schedule()
    }

    @Synchronized
    fun onProgress(bytesTransferred: Long) {
        if (stopped) return
        if (bytesTransferred > maxBytes) {
            maxBytes = bytesTransferred
            schedule()
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    private fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(stallTimeoutMs)
            onStall()
        }
    }
}

private val VIDEO_PATH_REGEX = Regex("^videos/[^/]+/[^/]+$")

/** Extrae "videos/{uid}/{archivo}" de un download URL (".../o/videos%2Fuid%2Ffile?alt=..."); null si no encaja. */
internal fun storagePathFromDownloadUrl(url: String): String? {
    val encoded = url.substringAfter("/o/", "").substringBefore('?')
    if (encoded.isEmpty()) return null
    val path = try {
        java.net.URLDecoder.decode(encoded, "UTF-8")
    } catch (e: IllegalArgumentException) {
        return null
    }
    return path.takeIf { VIDEO_PATH_REGEX.matches(it) && !it.contains("..") }
}

@Singleton
class StorageRepository @Inject constructor(
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth
) {

    private val userId: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User not logged in")

    suspend fun uploadVideo(videoUri: Uri, contentType: String? = null): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val videoRef = storage.reference
                .child("videos/$userId/video_$timestamp.mp4")

            videoRef.putFile(videoUri, videoMetadata(contentType)).await()
            val downloadUrl = videoRef.downloadUrl.await()

            Result.success(downloadUrl.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "StorageRepository: Error subiendo archivo")
            Result.failure(e)
        }
    }

    suspend fun uploadThumbnail(bitmap: Bitmap): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val thumbnailRef = storage.reference
                .child("thumbnails/$userId/thumb_$timestamp.jpg")

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val data = baos.toByteArray()

            thumbnailRef.putBytes(data, thumbnailMetadata()).await()
            val downloadUrl = thumbnailRef.downloadUrl.await()

            Result.success(downloadUrl.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "StorageRepository: Error subiendo archivo")
            Result.failure(e)
        }
    }

    /**
     * Sube un video a Storage emitiendo el progreso en tiempo real.
     * Misma ruta de destino que [uploadVideo]. Pensado para UploadRepository,
     * que puede cancelar la corrutina que colecta este Flow para abortar la subida.
     */
    fun uploadVideoWithProgress(
        videoUri: Uri,
        contentType: String? = null,
        stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS
    ): Flow<UploadStatus> = callbackFlow {
        // Contrato: este flow NUNCA termina con excepción hacia el colector. Todo fallo
        // (usuario nulo, error de Storage, downloadUrl) se emite como UploadStatus.Error y
        // el canal se cierra SIN causa (close()), porque close(cause) relanzaría la
        // excepción en collect{} y, sin handler aguas arriba, tumbaría la app.
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Timber.w("StorageRepository: uploadVideoWithProgress sin usuario autenticado")
            trySend(UploadStatus.Error(UploadException.NotLoggedIn()))
            close()
            awaitClose()
            return@callbackFlow
        }

        val timestamp = System.currentTimeMillis()
        val videoRef: StorageReference
        val uploadTask: UploadTask
        try {
            videoRef = storage.reference.child("videos/$uid/video_$timestamp.mp4")
            uploadTask = videoRef.putFile(videoUri, videoMetadata(contentType))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "StorageRepository: No se pudo iniciar la subida de video")
            trySend(UploadStatus.Error(e))
            close()
            awaitClose()
            return@callbackFlow
        }

        // Decision terminal ATOMICA: exactamente una fuente (exito, fallo/cancelacion del SDK o
        // watchdog) puede reclamar el estado terminal; las demas se ignoran. Evita que un
        // Timeout y un Success/Cancelled compitan cuando coinciden en el tiempo.
        val terminalClaimed = AtomicBoolean(false)
        // true cuando el watchdog gano la decision: el ERROR_CANCELED resultante no es una
        // cancelacion del usuario y debe ignorarse (el estado terminal ya es Error).
        val stalled = AtomicBoolean(false)
        val watchdog = StallWatchdog(this, stallTimeoutMs) {
            // Si la transferencia ya termino (el callback de exito esta en camino), no se
            // declara timeout: se deja que el listener de exito reclame el estado.
            if (uploadTask.isComplete) return@StallWatchdog
            if (!terminalClaimed.compareAndSet(false, true)) return@StallWatchdog
            // Orden importante: flag ANTES de cancelar, para que el failureListener lo vea.
            stalled.set(true)
            Timber.w("StorageRepository: subida sin progreso durante ${stallTimeoutMs}ms; se cancela")
            try {
                uploadTask.cancel()
            } catch (e: Exception) {
                Timber.e(e, "StorageRepository: error cancelando subida atascada")
            }
            trySend(UploadStatus.Error(UploadException.Timeout()))
            close()
        }

        val progressListener = OnProgressListener<UploadTask.TaskSnapshot> { snapshot ->
            // Tras el corte del watchdog el SDK aun entrega un snapshot (task.cancel()); no debe
            // llegar un Progress despues del Error(Timeout) ya emitido.
            if (terminalClaimed.get()) return@OnProgressListener
            watchdog.onProgress(snapshot.bytesTransferred)
            val percent = if (snapshot.totalByteCount > 0) {
                (100 * snapshot.bytesTransferred / snapshot.totalByteCount).toInt()
            } else {
                0
            }
            Timber.d("StorageRepository: Progreso de subida de video: $percent%")
            trySend(UploadStatus.Progress(percent))
        }

        val successListener = OnSuccessListener<UploadTask.TaskSnapshot> {
            if (!terminalClaimed.compareAndSet(false, true)) return@OnSuccessListener
            watchdog.stop()
            // downloadUrl es async y no hay variante suspend fuera de una corrutina; se resuelve dentro del scope del callbackFlow
            launch {
                try {
                    val downloadUrl = videoRef.downloadUrl.await()
                    Timber.d("StorageRepository: Video subido exitosamente: $downloadUrl")
                    trySend(UploadStatus.Success(downloadUrl.toString()))
                    close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "StorageRepository: Error obteniendo downloadUrl del video")
                    trySend(UploadStatus.Error(e))
                    close()
                }
            }
        }

        val failureListener = OnFailureListener { exception ->
            if (!terminalClaimed.compareAndSet(false, true)) {
                // Cancelacion provocada por el watchdog (o fallo tras un exito ya reclamado):
                // el estado terminal ya fue decidido.
                Timber.d("StorageRepository: ignorando fallo posterior a un estado terminal (stalled=${stalled.get()})")
                return@OnFailureListener
            }
            watchdog.stop()
            // Una cancelación del UploadTask llega aquí como StorageException con
            // ERROR_CANCELED: es un flujo normal, se emite Cancelled y no Error.
            if (exception is StorageException && exception.errorCode == StorageException.ERROR_CANCELED) {
                Timber.d("StorageRepository: Subida de video cancelada")
                trySend(UploadStatus.Cancelled)
            } else {
                Timber.e(exception, "StorageRepository: Error subiendo video")
                trySend(UploadStatus.Error(exception))
            }
            close()
        }

        uploadTask.addOnProgressListener(progressListener)
        uploadTask.addOnSuccessListener(successListener)
        uploadTask.addOnFailureListener(failureListener)
        // Arranca con la subida: cubre tambien "0 bytes desde el inicio" (sin red).
        watchdog.start()

        awaitClose {
            watchdog.stop()
            uploadTask.removeOnProgressListener(progressListener)
            uploadTask.removeOnSuccessListener(successListener)
            uploadTask.removeOnFailureListener(failureListener)
            // Si el colector se cancela antes de completar, cancelamos la subida real para no dejar huérfanos en Storage
            if (!uploadTask.isComplete) {
                uploadTask.cancel()
            }
        }
    }
        // Buffer ilimitado: un Progress nunca debe desplazar al estado terminal (con el
        // buffer por defecto de 64 lleno, trySend fallaría y la UI quedaría en Uploading).
        .buffer(Channel.UNLIMITED)

    /**
     * Borra el archivo de Storage al que apunta un download URL previamente emitido por
     * este mismo proyecto (ver isValidStorageUrl en firestore.rules). Idempotente: una URL
     * vacía (como el thumbnailUrl opcional de un video sin thumbnail) o un archivo que ya
     * no existe se tratan como éxito, no como error — para que el llamador no tenga que
     * distinguir "nunca hubo archivo" de "ya se borró".
     */
    suspend fun deleteFileByUrl(url: String): Result<Unit> {
        if (url.isBlank()) return Result.success(Unit)

        return try {
            val ref = try {
                storage.getReferenceFromUrl(url)
            } catch (e: IllegalArgumentException) {
                // URL con host/bucket que el SDK no reconoce (p. ej. emulador): se intenta por path.
                val path = storagePathFromDownloadUrl(url) ?: throw e
                Timber.w("StorageRepository: getReferenceFromUrl fallo; borrando por path $path")
                storage.reference.child(path)
            }
            ref.delete().await()
            Result.success(Unit)
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Result.success(Unit)
            } else {
                Timber.e(e, "StorageRepository: Error borrando archivo de Storage: $url")
                Result.failure(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "StorageRepository: Error borrando archivo de Storage: $url")
            Result.failure(e)
        }
    }
}