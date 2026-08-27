package com.negociodigital.boomday.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.util.currentDayKeyBogota
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_DURATION_SECONDS = 3
private const val MAX_DURATION_SECONDS = 60

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
        hashtags: List<String>
    ): Flow<UploadStatus> = flow {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.w("UploadRepository: uploadVideoComplete llamado sin usuario autenticado")
            emit(UploadStatus.Error(IllegalStateException("Debes iniciar sesión para subir un video")))
            return@flow
        }

        val durationSeconds = try {
            readVideoDurationSeconds(videoUri)
        } catch (e: Exception) {
            Timber.e(e, "UploadRepository: Error leyendo duración del video: $videoUri")
            emit(UploadStatus.Error(IllegalArgumentException("No se pudo leer la duración del video", e)))
            return@flow
        }

        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            Timber.w("UploadRepository: Duración fuera de rango ($durationSeconds s) para $videoUri")
            emit(
                UploadStatus.Error(
                    IllegalArgumentException(
                        "El video debe durar entre $MIN_DURATION_SECONDS y $MAX_DURATION_SECONDS segundos (actual: ${durationSeconds}s)"
                    )
                )
            )
            return@flow
        }

        storageRepository.uploadVideoWithProgress(videoUri).collect { status ->
            when (status) {
                is UploadStatus.Progress -> emit(status)

                is UploadStatus.Cancelled -> emit(status)

                is UploadStatus.Error -> {
                    Timber.e(status.throwable, "UploadRepository: Error subiendo video a Storage")
                    emit(status)
                }

                is UploadStatus.Success -> {
                    val videoUrl = status.downloadUrl

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

                    val saveResult = videoRepository.saveVideo(video)
                    saveResult.fold(
                        onSuccess = { videoId ->
                            Timber.d("UploadRepository: Subida completa. videoId=$videoId")
                            emit(UploadStatus.Success(videoId))
                        },
                        onFailure = { error ->
                            // El blob ya está en Storage y NO se borra automáticamente (no hay job de
                            // limpieza todavía) para no arriesgar borrar algo recuperable: queda huérfano
                            // y debe limpiarse manualmente con la URL de este log.
                            Timber.e(
                                error,
                                "UploadRepository: Video subido a Storage pero saveVideo falló. " +
                                    "Blob huérfano en: $videoUrl"
                            )
                            emit(UploadStatus.Error(error))
                        }
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

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
