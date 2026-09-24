package com.negociodigital.boomday.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.util.currentDayKeyBogota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val storageRepository: StorageRepository
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
    }.flowOn(Dispatchers.IO)

    /**
     * Obtiene los videos más vistos del día calendario actual (zona horaria
     * America/Bogota), ordenados por vistas descendente.
     *
     * FIX: antes esta query combinaba whereGreaterThan("createdAt", yesterday) con
     * orderBy("views", DESC) — Firestore exige que, si hay un filtro de desigualdad, el
     * primer orderBy recaiga sobre ESE MISMO campo. El SDK lanzaba IllegalArgumentException
     * de forma síncrona al construir la Query, dejando el Ranking en Error permanente. Se
     * reemplaza el filtro de desigualdad por una igualdad sobre `dayKey` (mismo día
     * calendario), lo que sí es válido combinado con orderBy("views") porque ya no hay
     * desigualdad involucrada.
     */
    fun getTopVideos(limit: Int = 10): Flow<List<Video>> = callbackFlow {
        val listener = videosCollection
            .whereEqualTo("dayKey", currentDayKeyBogota())
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
    }.flowOn(Dispatchers.IO)

    /**
     * Incrementa el contador de vistas de un video. Solo cuenta una vista por usuario.
     *
     * FIX (seguridad): antes esto eran dos escrituras SEPARADAS y no atómicas (primero
     * viewRef.set(...), después el update de "views"), lo que dejaba una ventana para que
     * un cliente modificado llamara directo al update de "views" sin pasar nunca por la
     * creación del subdocumento de dedupe, inflando vistas repetidamente. Ahora ambas
     * escrituras van dentro de una única transacción de Firestore: o se crea el
     * subdocumento de dedupe Y se incrementa el contador juntos, o no se hace ninguna de
     * las dos. La regla `views` en firestore.rules exige además, con existsAfter(), que el
     * subdocumento de dedupe exista al final de esa misma operación atómica — así que ya
     * no basta con llamar al update aislado, ni siquiera saltándose este repositorio.
     */
    suspend fun incrementViews(videoId: String): Result<Unit> {
        return try {
            val userId = auth.currentUser?.uid
                ?: return Result.failure(Exception("Usuario no autenticado"))

            val videoRef = videosCollection.document(videoId)
            val viewRef = videoRef.collection("views").document(userId)

            firestore.runTransaction { transaction ->
                val viewDoc = transaction.get(viewRef)

                if (!viewDoc.exists()) {
                    // Registrar la vista del usuario e incrementar el contador en la misma
                    // transacción: ambas escrituras se aplican atómicamente o ninguna lo hace.
                    transaction.set(viewRef, mapOf("viewedAt" to System.currentTimeMillis()))
                    transaction.update(videoRef, "views", FieldValue.increment(1))
                }

                null
            }.await()

            Timber.d("VideoRepository: Vista incrementada para video: $videoId")
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
     * Elimina un video: el documento de Firestore y los blobs de Storage (video +
     * thumbnail) que le pertenecían.
     *
     * FIX (Storage huérfano): antes esto solo borraba el documento de Firestore — el
     * blob de video en Storage quedaba huérfano para siempre, ni siquiera la Cloud
     * Function de expiración lo encuentra después (busca por documentos que ya no
     * existen). El documento se borra primero para que el video desaparezca del
     * Feed/Ranking de inmediato; el borrado de Storage es best-effort después (mismo
     * criterio que cleanupExpiredVideos en functions/): si un blob falla en borrarse,
     * se loguea pero no revierte ni falla la operación completa, porque desde la
     * perspectiva del usuario el video ya fue "eliminado" con éxito.
     *
     * Nota: la subcolección videos/{videoId}/views (dedupe de vistas) no se borra
     * acá — el SDK cliente no puede hacer recursiveDelete, solo el Admin SDK (eso ya
     * lo cubre cleanupExpiredVideos para videos vencidos; un video borrado manualmente
     * por su dueño antes de las 24h deja esa subcolección huérfana, de bajo impacto
     * porque es solo metadata de dedupe, no contenido visible).
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

            storageRepository.deleteFileByUrl(video.videoUrl)
                .onFailure { Timber.e(it, "VideoRepository: video eliminado pero falló borrar el blob de video en Storage: $videoId") }
            storageRepository.deleteFileByUrl(video.thumbnailUrl)
                .onFailure { Timber.e(it, "VideoRepository: video eliminado pero falló borrar el thumbnail en Storage: $videoId") }

            Timber.d("VideoRepository: Video eliminado: $videoId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "VideoRepository: Error eliminando video")
            Result.failure(e)
        }
    }
}