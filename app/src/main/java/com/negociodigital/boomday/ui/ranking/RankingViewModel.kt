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
            val blockedUserIds = auth.currentUser?.uid?.let { userRepository.getBlockedUsers(it) } ?: emptySet()
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
