package com.negociodigital.boomday.ui.upload

import androidx.annotation.StringRes
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.UploadException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Traduce cualquier Throwable del flujo de subida a un string en español para el usuario.
 * Nunca se usa e.message (texto técnico/en inglés de Firebase).
 */
@StringRes
fun uploadErrorMessageRes(t: Throwable): Int = when (t) {
    is UploadException.NotLoggedIn -> R.string.upload_error_not_logged_in
    is UploadException.InvalidDuration -> R.string.upload_error_invalid_duration
    is UploadException.CannotReadVideo -> R.string.upload_error_read_video
    is UploadException.Timeout -> R.string.upload_error_timeout
    is UploadException.Incomplete -> R.string.upload_error_unknown
    is SocketTimeoutException -> R.string.upload_error_timeout
    is FirebaseNetworkException -> R.string.upload_error_network
    is StorageException -> when (t.errorCode) {
        StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> R.string.upload_error_timeout
        StorageException.ERROR_NOT_AUTHENTICATED,
        StorageException.ERROR_NOT_AUTHORIZED -> R.string.upload_error_permission
        StorageException.ERROR_QUOTA_EXCEEDED -> R.string.upload_error_quota
        else -> if (t.cause is IOException) R.string.upload_error_network else R.string.upload_error_unknown
    }
    is FirebaseFirestoreException -> when (t.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        FirebaseFirestoreException.Code.UNAUTHENTICATED -> R.string.upload_error_permission
        FirebaseFirestoreException.Code.UNAVAILABLE -> R.string.upload_error_network
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> R.string.upload_error_timeout
        else -> R.string.upload_error_unknown
    }
    is IOException -> R.string.upload_error_network
    else -> R.string.upload_error_unknown
}
