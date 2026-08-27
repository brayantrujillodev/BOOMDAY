package com.negociodigital.boomday.ui.feed

import com.negociodigital.boomday.data.model.Video

/**
 * Estado de la pantalla de Feed.
 *
 * Diverge del patrón de enum plano (ver historial de este mismo archivo) siguiendo el
 * precedente de UploadState: varios estados necesitan datos adjuntos (la lista de videos
 * ya cargada, o el mensaje de error).
 */
sealed interface FeedState {
    data object Loading : FeedState
    data class Success(val videos: List<Video>) : FeedState
    data class Refreshing(val videos: List<Video>) : FeedState
    data object Empty : FeedState
    data class Error(val message: String) : FeedState
}
