package com.negociodigital.boomday.data.repository

import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.storage.UploadTask
import com.negociodigital.boomday.data.model.UploadStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepository @Inject constructor(
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth
) {

    private val userId: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User not logged in")

    suspend fun uploadVideo(videoUri: Uri): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val videoRef = storage.reference
                .child("videos/$userId/video_$timestamp.mp4")

            videoRef.putFile(videoUri).await()
            val downloadUrl = videoRef.downloadUrl.await()

            Result.success(downloadUrl.toString())
        } catch (e: Exception) {
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

            thumbnailRef.putBytes(data).await()
            val downloadUrl = thumbnailRef.downloadUrl.await()

            Result.success(downloadUrl.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sube un video a Storage emitiendo el progreso en tiempo real.
     * Misma ruta de destino que [uploadVideo]. Pensado para UploadRepository,
     * que puede cancelar la corrutina que colecta este Flow para abortar la subida.
     */
    fun uploadVideoWithProgress(videoUri: Uri): Flow<UploadStatus> = callbackFlow {
        val timestamp = System.currentTimeMillis()
        val videoRef = storage.reference
            .child("videos/$userId/video_$timestamp.mp4")

        val uploadTask = videoRef.putFile(videoUri)

        val progressListener = OnProgressListener<UploadTask.TaskSnapshot> { snapshot ->
            val percent = if (snapshot.totalByteCount > 0) {
                (100 * snapshot.bytesTransferred / snapshot.totalByteCount).toInt()
            } else {
                0
            }
            Timber.d("StorageRepository: Progreso de subida de video: $percent%")
            trySend(UploadStatus.Progress(percent))
        }

        val successListener = OnSuccessListener<UploadTask.TaskSnapshot> {
            // downloadUrl es async y no hay variante suspend fuera de una corrutina; se resuelve dentro del scope del callbackFlow
            launch {
                try {
                    val downloadUrl = videoRef.downloadUrl.await()
                    Timber.d("StorageRepository: Video subido exitosamente: $downloadUrl")
                    trySend(UploadStatus.Success(downloadUrl.toString()))
                    close()
                } catch (e: Exception) {
                    Timber.e(e, "StorageRepository: Error obteniendo downloadUrl del video")
                    trySend(UploadStatus.Error(e))
                    close(e)
                }
            }
        }

        val failureListener = OnFailureListener { exception ->
            Timber.e(exception, "StorageRepository: Error subiendo video")
            trySend(UploadStatus.Error(exception))
            close(exception)
        }

        uploadTask.addOnProgressListener(progressListener)
        uploadTask.addOnSuccessListener(successListener)
        uploadTask.addOnFailureListener(failureListener)

        awaitClose {
            uploadTask.removeOnProgressListener(progressListener)
            uploadTask.removeOnSuccessListener(successListener)
            uploadTask.removeOnFailureListener(failureListener)
            // Si el colector se cancela antes de completar, cancelamos la subida real para no dejar huérfanos en Storage
            if (!uploadTask.isComplete) {
                uploadTask.cancel()
            }
        }
    }
}