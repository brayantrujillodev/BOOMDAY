package com.negociodigital.boomday.ui.feed

import androidx.annotation.StringRes
import com.negociodigital.boomday.data.model.Video

/**
 * Estado de la pantalla de Feed.
 *
 * Diverge del patrón de enum plano (ver historial de este mismo archivo) siguiendo el
 * precedente de UploadState: varios estados necesitan datos adjuntos (la lista de videos
 * ya cargada, o el mensaje de error).
 */
sealed interface NextPageState {
    /** Hay (potencialmente) más páginas y no se está cargando ninguna. */
    data object Idle : NextPageState
    data object Loading : NextPageState
    /** La última carga de página falló; lo ya cargado se conserva y se puede reintentar. */
    data object Error : NextPageState
    /** No quedan más páginas. */
    data object End : NextPageState
}

sealed interface FeedState {
    data object Loading : FeedState
    data class Success(val videos: List<Video>, val nextPage: NextPageState = NextPageState.Idle) : FeedState
    data class Refreshing(val videos: List<Video>) : FeedState
    data object Empty : FeedState
    /** [messageRes] es un string resource en español (nunca el mensaje crudo del SDK). */
    data class Error(@StringRes val messageRes: Int) : FeedState
}
