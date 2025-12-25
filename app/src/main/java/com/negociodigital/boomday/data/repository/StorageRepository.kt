package com.negociodigital.boomday.data.repository

import android.graphics.Bitmap
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
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
}