package com.negociodigital.boomday.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.util.currentDayKeyBogota
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_DURATION_SECONDS = 3
private const val MAX_DURATION_SECONDS = 60
private const val SAVE_TIMEOUT_MS = 30_000L

/**
 * Orquesta el flujo completo de subida de un video: valida duración, sube el blob a
 * Storage (delegando en StorageRepository) y persiste el documento en Firestore
 * (delegando en VideoRepository). No asume que [videoUri] viene de una grabación de
 * cámara: funciona igual para un video importado desde la galería.
 */
@Singleton
class UploadRepository @Inject constructor(
    private val storageRepository: StorageRepository,
    private val videoRepository: VideoRepository,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) {

    /**
     * Sube un video validando primero su duración real (3-60s) para no gastar ancho de
     * banda subiendo algo que será rechazado. Emite el progreso de Storage tal cual y,
     * al terminar la subida, crea el documento del video en Firestore.
     *
     * Nota de diseño: la emisión final de éxito reutiliza [UploadStatus.Success] (definida
     * para el caso de StorageRepository, cuyo campo `downloadUrl` es una URL de blob). Aquí,
     * en la última emisión de este flow, `downloadUrl` contiene en realidad el `videoId` que
     * devuelve Firestore (no una URL) — es una decisión deliberada para no duplicar el sealed
     * type; el ViewModel que consume este flow debe leerlo como tal.
     */
    fun uploadVideoComplete(
        videoUri: Uri,
        title: String,
        description: String,
        hashtags: List<String>,
        videoId: String? = null
    ): Flow<UploadStatus> = flow {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.w("UploadRepository: uploadVideoComplete llamado sin usuario autenticado")
            emit(UploadStatus.Error(UploadException.NotLoggedIn()))
            return@flow
        }

        val durationSeconds = try {
            durationReader(videoUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "UploadRepository: Error leyendo duración del video: $videoUri")
            emit(UploadStatus.Error(UploadException.CannotReadVideo(e)))
            return@flow
        }

        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            Timber.w("UploadRepository: Duración fuera de rango ($durationSeconds s) para $videoUri")
            emit(UploadStatus.Error(UploadException.InvalidDuration(durationSeconds)))
            return@flow
        }

        var terminal = false
        // .catch cubre cualquier excepción que escape del flow de Storage (defensa en
        // profundidad: StorageRepository ya no debería lanzar). CancellationException se
        // relanza siempre: nunca es un Error.
        storageRepository.uploadVideoWithProgress(
            videoUri,
            runCatching { context.contentResolver.getType(videoUri) }.getOrNull()
        )
            .catch { e ->
                if (e is CancellationException) throw e
                Timber.e(e, "UploadRepository: El flow de Storage terminó con excepción")
                emit(UploadStatus.Error(e))
            }
            .collect { status ->
                when (status) {
                    is UploadStatus.Progress -> emit(status)

                    is UploadStatus.Cancelled -> {
                        terminal = true
                        emit(status)
                    }

                    is UploadStatus.Error -> {
                        terminal = true
                        Timber.e(status.throwable, "UploadRepository: Error subiendo video a Storage")
                        emit(status)
                    }

                    is UploadStatus.Success -> {
                        terminal = true
                        emit(saveVideoDocument(currentUser, status.downloadUrl, title, description, hashtags, durationSeconds, videoId))
                    }
                }
            }

        if (!terminal) {
            Timber.e("UploadRepository: El flow de Storage terminó sin estado terminal")
            emit(UploadStatus.Error(UploadException.Incomplete()))
        }
    }.catch { e ->
        // Último recurso para que ninguna excepción llegue al colector (ViewModel).
        if (e is CancellationException) throw e
        Timber.e(e, "UploadRepository: Excepción inesperada en uploadVideoComplete")
        emit(UploadStatus.Error(e))
    }.flowOn(Dispatchers.IO)

    /**
     * Crea el documento en Firestore. Comportamiento ante fallo (P0-2):
     * - Se escribe con id determinista [videoId] (si lo da el llamador): reintentar la
     *   publicación sobrescribe el mismo documento, jamás crea uno segundo.
     * - Timeout de escritura (típico sin red: Firestore encola la escritura y set().await()
     *   no vuelve): el blob NO se borra, porque la escritura aún podría confirmarse; el
     *   reintento reutiliza el mismo videoId y la última escritura gana.
     * - Fallo definitivo (reglas, etc.): el blob subido queda huérfano e inalcanzable, se
     *   intenta borrar best-effort (deleteFileByUrl es idempotente) y se informa Error.
     */
    private suspend fun saveVideoDocument(
        currentUser: FirebaseUser,
        videoUrl: String,
        title: String,
        description: String,
        hashtags: List<String>,
        durationSeconds: Int,
        videoId: String?
    ): UploadStatus {
        val video = Video(
            userId = currentUser.uid,
            userName = currentUser.displayName.orEmpty(),
            userAvatar = currentUser.photoUrl?.toString().orEmpty(),
            videoUrl = videoUrl,
            title = title,
            description = description,
            hashtags = hashtags,
            duration = durationSeconds,
            dayKey = currentDayKeyBogota()
        )

        // withTimeoutOrNull devuelve null al vencer, sin lanzar TimeoutCancellationException
        // (que es una CancellationException y se confundiría con una cancelación real).
        val saveResult = try {
            withTimeoutOrNull(saveTimeoutMs) { videoRepository.saveVideo(video, videoId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "UploadRepository: saveVideo lanzó excepción")
            Result.failure(e)
        }

        if (saveResult == null) {
            Timber.e("UploadRepository: saveVideo excedió ${saveTimeoutMs}ms. Blob en: $videoUrl (no se borra, la escritura puede confirmarse)")
            return UploadStatus.Error(UploadException.Timeout())
        }

        return saveResult.fold(
            onSuccess = { id ->
                Timber.d("UploadRepository: Subida completa. videoId=$id")
                UploadStatus.Success(id)
            },
            onFailure = { error ->
                if (error is UploadException.AlreadyPublished) {
                    // Un intento previo ya publicó videos/{id}: éxito idempotente. El blob recién
                    // subido en este reintento no lo referencia nadie (el doc apunta al anterior).
                    Timber.d("UploadRepository: ${error.videoId} ya publicado; se borra el blob del reintento")
                    withContext(NonCancellable) {
                        storageRepository.deleteFileByUrl(videoUrl).onFailure {
                            Timber.e(it, "UploadRepository: No se pudo borrar el blob duplicado: $videoUrl")
                        }
                    }
                    return@fold UploadStatus.Success(error.videoId)
                }
                Timber.e(error, "UploadRepository: Video subido a Storage pero saveVideo falló. Blob: $videoUrl")
                withContext(NonCancellable) {
                    storageRepository.deleteFileByUrl(videoUrl).onFailure {
                        Timber.e(it, "UploadRepository: No se pudo borrar el blob huérfano: $videoUrl")
                    }
                }
                UploadStatus.Error(error)
            }
        )
    }

    /** Timeout de la escritura en Firestore. internal var solo para pruebas. */
    internal var saveTimeoutMs: Long = SAVE_TIMEOUT_MS

    /** Lector de duración (segundos). internal var solo para pruebas JVM (MediaMetadataRetriever es de Android). */
    internal var durationReader: (Uri) -> Int = this::readVideoDurationSeconds

    /**
     * Lee la duración real (en segundos) del video apuntado por [videoUri] usando
     * MediaMetadataRetriever. El retriever se libera siempre, incluso si falla la lectura.
     */
    private fun readVideoDurationSeconds(videoUri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: throw IllegalStateException("No se pudo extraer la duración del video: $videoUri")
            (durationMs / 1000L).toInt()
        } finally {
            retriever.release()
        }
    }
}
