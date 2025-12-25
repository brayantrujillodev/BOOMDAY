package com.negociodigital.boomday.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.negociodigital.boomday.data.model.Video
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {

    private val videosCollection = firestore.collection("videos")

    /**
     * Guarda un video en Firestore
     */
    suspend fun saveVideo(video: Video): Result<String> {
        return try {
            val docRef = videosCollection.document()
            val videoWithId = video.copy(videoId = docRef.id)
            docRef.set(videoWithId).await()

            Timber.d("VideoRepository: Video guardado exitosamente: ${docRef.id}")
            Result.success(docRef.id)
        } catch (e: Exception) {
            Timber.e(e, "VideoRepository: Error guardando video")
            Result.failure(e)
        }
    }

    /**
     * Obtiene los videos de las últimas 24 horas
     */
    fun getTodayVideos(): Flow<List<Video>> = callbackFlow {
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000)

        val listener = videosCollection
            .whereGreaterThan("createdAt", yesterday)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "VideoRepository: Error obteniendo videos de hoy")
                    close(error)
                    return@addSnapshotListener
                }

                val videos = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Video::class.java)?.copy(videoId = doc.id)
                    } catch (e: Exception) {
                        Timber.e(e, "VideoRepository: Error parseando video: ${doc.id}")
                        null
                    }
                } ?: emptyList()

                trySend(videos)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Obtiene los videos más vistos (top videos)
     */
    fun getTopVideos(limit: Int = 10): Flow<List<Video>> = callbackFlow {
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000)

        val listener = videosCollection
            .whereGreaterThan("createdAt", yesterday)
            .orderBy("views", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "VideoRepository: Error obteniendo top videos")
                    close(error)
                    return@addSnapshotListener
                }

                val videos = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Video::class.java)?.copy(videoId = doc.id)
                    } catch (e: Exception) {
                        Timber.e(e, "VideoRepository: Error parseando video: ${doc.id}")
                        null
                    }
                } ?: emptyList()

                trySend(videos)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Incrementa el contador de vistas de un video
     * Solo cuenta una vista por usuario
     */
    suspend fun incrementViews(videoId: String): Result<Unit> {
        return try {
            val userId = auth.currentUser?.uid
                ?: return Result.failure(Exception("Usuario no autenticado"))

            val viewRef = videosCollection
                .document(videoId)
                .collection("views")
                .document(userId)

            val viewDoc = viewRef.get().await()

            if (!viewDoc.exists()) {
                // Registrar la vista del usuario
                viewRef.set(mapOf("viewedAt" to System.currentTimeMillis())).await()

                // Incrementar contador de vistas
                videosCollection.document(videoId)
                    .update("views", FieldValue.increment(1))
                    .await()

                Timber.d("VideoRepository: Vista incrementada para video: $videoId")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "VideoRepository: Error incrementando vistas")
            Result.failure(e)
        }
    }

    /**
     * Obtiene un video por ID
     */
    suspend fun getVideoById(videoId: String): Result<Video?> {
        return try {
            val doc = videosCollection.document(videoId).get().await()
            val video = doc.toObject(Video::class.java)?.copy(videoId = doc.id)

            Result.success(video)
        } catch (e: Exception) {
            Timber.e(e, "VideoRepository: Error obteniendo video por ID: $videoId")
            Result.failure(e)
        }
    }

    /**
     * Elimina un video
     */
    suspend fun deleteVideo(videoId: String): Result<Unit> {
        return try {
            val userId = auth.currentUser?.uid
                ?: return Result.failure(Exception("Usuario no autenticado"))

            val video = videosCollection.document(videoId).get().await()
                .toObject(Video::class.java)

            if (video?.userId != userId) {
                return Result.failure(Exception("No tienes permiso para eliminar este video"))
            }

            videosCollection.document(videoId).delete().await()

            Timber.d("VideoRepository: Video eliminado: $videoId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "VideoRepository: Error eliminando video")
            Result.failure(e)
        }
    }
}