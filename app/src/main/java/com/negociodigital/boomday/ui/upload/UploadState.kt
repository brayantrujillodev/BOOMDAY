package com.negociodigital.boomday.ui.upload

import android.net.Uri

/**
 * Estado de la pantalla de subida de video.
 *
 * Diverge intencionalmente del patrón de data class plana (AuthState) / enum (FeedState)
 * usado en el resto del proyecto porque varios estados necesitan datos adjuntos
 * (uri grabado, progreso, videoId, mensaje de error). Es la primera excepción
 * documentada de este patrón en BoomDay.
 */
sealed interface UploadState {
    data object Idle : UploadState
    data class Recorded(val uri: Uri, val durationMs: Long) : UploadState
    data class Uploading(val progress: Int) : UploadState
    data class Success(val videoId: String) : UploadState
    data class Error(val message: String) : UploadState
    data object Cancelled : UploadState
}
