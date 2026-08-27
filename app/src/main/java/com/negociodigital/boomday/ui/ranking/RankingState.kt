package com.negociodigital.boomday.ui.ranking

import com.negociodigital.boomday.data.model.Video

/**
 * Video ya posicionado en el ranking. `position` es un índice simple 1-based sobre el
 * orden que ya entrega VideoRepository.getTopVideos() (views DESCENDING): dos videos con
 * el mismo número de views NO comparten posición (sin posiciones densas por empate).
 */
data class RankedVideo(val position: Int, val video: Video)

sealed interface RankingState {
    data object Loading : RankingState
    data class Success(val ranking: List<RankedVideo>) : RankingState
    data object Empty : RankingState
    data class Error(val message: String) : RankingState
}
