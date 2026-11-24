package com.negociodigital.boomday.ui.ranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.negociodigital.boomday.R
import kotlinx.coroutines.Job
import java.io.IOException
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

    private var loadJob: Job? = null

    init {
        Timber.d("RankingViewModel: Inicializando")
        loadRanking()
    }

    /**
     * Reintenta la carga solo si el estado actual es Error. Cancela el job anterior (su
     * awaitClose desregistra el listener de Firestore), pasa a Loading y relanza.
     * Una segunda pulsación inmediata ve Loading y no hace nada.
     */
    fun retry() {
        if (_rankingState.value !is RankingState.Error) return
        _rankingState.value = RankingState.Loading
        loadRanking()
    }

    private fun errorFor(e: Throwable): RankingState.Error = RankingState.Error(
        if (e is IOException || e is FirebaseNetworkException ||
            (e is FirebaseFirestoreException &&
                (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                    e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED))
        ) R.string.ranking_error_offline else R.string.ranking_error_generic
    )

    private fun loadRanking() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Fail-closed: si no se pudo leer la lista de bloqueados, no se muestra el ranking.
            val uid = auth.currentUser?.uid
            var blockedUserIds: Set<String> = emptySet()
            if (uid != null) {
                val result = withTimeoutOrNull(BLOCKED_LOAD_TIMEOUT_MS) { userRepository.getBlockedUsers(uid) }
                if (result == null) {
                    Timber.e("RankingViewModel: Timeout obteniendo usuarios bloqueados")
                    _rankingState.value = RankingState.Error(R.string.ranking_error_offline)
                    return@launch
                }
                blockedUserIds = result.getOrElse { e ->
                    Timber.e(e, "RankingViewModel: Error obteniendo usuarios bloqueados")
                    _rankingState.value = errorFor(e)
                    return@launch
                }
            }
            videoRepository.getTopVideos()
                .map { videos -> videos.filterNot { it.userId in blockedUserIds } }
                .catch { e ->
                    Timber.e(e, "RankingViewModel: Error cargando ranking")
                    _rankingState.value = errorFor(e)
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
