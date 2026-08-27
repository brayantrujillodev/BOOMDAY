package com.negociodigital.boomday.data.model

/**
 * Representa el estado de una subida a Firebase Storage / Firestore.
 * Usado por StorageRepository.uploadVideoWithProgress y UploadRepository.uploadVideoComplete.
 */
sealed interface UploadStatus {
    data class Progress(val percent: Int) : UploadStatus
    data class Success(val downloadUrl: String) : UploadStatus
    data class Error(val throwable: Throwable) : UploadStatus
    data object Cancelled : UploadStatus
}
