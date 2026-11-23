package com.negociodigital.boomday.data.model

/**
 * Errores de dominio del flujo de subida. Se transportan dentro de [UploadStatus.Error]
 * (nunca se lanzan al colector del Flow) y la capa de UI los traduce a un mensaje en
 * español mediante `uploadErrorMessageRes`, sin exponer el message crudo de Firebase.
 */
sealed class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotLoggedIn : UploadException("User not logged in")
    class CannotReadVideo(cause: Throwable? = null) : UploadException("Cannot read video metadata", cause)
    class InvalidDuration(val seconds: Int) : UploadException("Video duration out of range: ${seconds}s")
    class Timeout(cause: Throwable? = null) : UploadException("Upload timed out", cause)

    /** videos/{videoId} ya existe y es del mismo usuario: un intento previo sí se publicó (éxito idempotente). */
    class AlreadyPublished(val videoId: String) : UploadException("Video already published: $videoId")

    /** videos/{videoId} ya existe pero pertenece a otro usuario: no se pisa. */
    class IdConflict(val videoId: String) : UploadException("Video id belongs to another user: $videoId")

    /** El flow terminó sin emitir Success/Error/Cancelled (no debería ocurrir). */
    class Incomplete : UploadException("Upload flow ended without terminal status")
}
