package com.negociodigital.boomday.data.repository

import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import kotlinx.coroutines.CancellationException
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

            thumbnailRef.putBytes(data).await()
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
    fun uploadVideoWithProgress(videoUri: Uri): Flow<UploadStatus> = callbackFlow {
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
            uploadTask = videoRef.putFile(videoUri)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "StorageRepository: No se pudo iniciar la subida de video")
            trySend(UploadStatus.Error(e))
            close()
            awaitClose()
            return@callbackFlow
        }

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
            storage.getReferenceFromUrl(url).delete().await()
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