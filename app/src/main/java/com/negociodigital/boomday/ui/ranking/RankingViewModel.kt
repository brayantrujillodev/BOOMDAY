package com.negociodigital.boomday.ui.ranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.negociodigital.boomday.ui.feed.BLOCKED_LOAD_TIMEOUT_MS
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class RankingViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val userRepository: UserRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _rankingState = MutableStateFlow<RankingState>(RankingState.Loading)
    val rankingState: StateFlow<RankingState> = _rankingState.asStateFlow()

    init {
        Timber.d("RankingViewModel: Inicializando")
        loadRanking()
    }

    private fun loadRanking() {
        viewModelScope.launch {
            // Fail-closed: si no se pudo leer la lista de bloqueados, no se muestra el ranking.
            val uid = auth.currentUser?.uid
            var blockedUserIds: Set<String> = emptySet()
            if (uid != null) {
                val result = withTimeoutOrNull(BLOCKED_LOAD_TIMEOUT_MS) { userRepository.getBlockedUsers(uid) }
                if (result == null) {
                    Timber.e("RankingViewModel: Timeout obteniendo usuarios bloqueados")
                    _rankingState.value = RankingState.Error("Sin conexión")
                    return@launch
                }
                blockedUserIds = result.getOrElse { e ->
                    Timber.e(e, "RankingViewModel: Error obteniendo usuarios bloqueados")
                    _rankingState.value = RankingState.Error(e.message ?: "Error al cargar el ranking")
                    return@launch
                }
            }
            videoRepository.getTopVideos()
                .map { videos -> videos.filterNot { it.userId in blockedUserIds } }
                .catch { e ->
                    Timber.e(e, "RankingViewModel: Error cargando ranking")
                    _rankingState.value = RankingState.Error(e.message ?: "Error al cargar el ranking")
                }
                .collect { videos ->
                    _rankingState.value = if (videos.isEmpty()) {
                        RankingState.Empty
                    } else {
                        RankingState.Success(
                            videos.mapIndexed { index, video ->
                                RankedVideo(position = index + 1, video = video)
                            }
                        )
                    }
                }
        }
    }
}
