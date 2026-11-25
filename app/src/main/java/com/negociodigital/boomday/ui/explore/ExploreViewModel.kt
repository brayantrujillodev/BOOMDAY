package com.negociodigital.boomday.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.google.firebase.auth.FirebaseAuth
import com.negociodigital.boomday.R
import com.negociodigital.boomday.ui.util.isOfflineError
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

data class ExploreUiState(
    val isLoading: Boolean = true,
    /** String resource en español (nunca el mensaje crudo del SDK). */
    @StringRes val error: Int? = null,
    val trending: List<Video> = emptyList(),
    val recent: List<Video> = emptyList(),
    val query: String = ""
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val userRepository: UserRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    /** Listas sin filtrar por query (ya sin usuarios bloqueados). */
    private val source = MutableStateFlow(ExploreUiState())
    private val query = MutableStateFlow("")

    val uiState: StateFlow<ExploreUiState> = combine(source, query) { src, q ->
        val needle = q.trim()
        src.copy(
            query = q,
            trending = src.trending.filterByQuery(needle),
            recent = src.recent.filterByQuery(needle)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExploreUiState())

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onQueryChange(q: String) {
        query.value = q
    }

    /** Idempotente: con una carga en curso no se relanza (evita dobles cargas por doble tap). */
    fun refresh() {
        if (source.value.isLoading && loadJob?.isActive == true) return
        load()
    }

    private var autoRetried = false

    /**
     * ON_RESUME de la pestaña: si Explorar está en Error sin datos, reintenta UNA vez
     * (la bandera se limpia al cargar sin error), sin polling ni bucles.
     */
    fun onScreenResumed() {
        val st = source.value
        if (st.error != null && !st.isLoading && !autoRetried) {
            autoRetried = true
            load()
        }
    }

    private fun errorFor(e: Throwable): Int =
        if (e.isOfflineError()) R.string.explore_error_offline else R.string.explore_error_generic

    private fun load() {
        loadJob?.cancel()
        source.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            // Fail-closed: sin la lista de bloqueados no se muestra contenido sin filtrar.
            val blockedUserIds: Set<String>? = try {
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    auth.currentUser?.uid?.let { userRepository.getBlockedUsers(it).getOrThrow() } ?: emptySet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "ExploreViewModel: Error obteniendo usuarios bloqueados")
                source.update { it.copy(isLoading = false, error = errorFor(e)) }
                return@launch
            }
            if (blockedUserIds == null) {
                Timber.e("ExploreViewModel: Timeout obteniendo usuarios bloqueados")
                source.update { it.copy(isLoading = false, error = R.string.explore_error_offline) }
                return@launch
            }

            // Ambos flujos corren en Main (viewModelScope), así que estos contadores son seguros.
            var pending = 2
            var timeoutJob: Job? = null
            fun settle() {
                pending--
                if (pending == 0) {
                    timeoutJob?.cancel()
                    source.update { it.copy(isLoading = false) }
                    if (source.value.error == null) autoRetried = false
                }
            }

            fun collectFlow(
                what: String,
                flow: Flow<List<Video>>,
                apply: (ExploreUiState, List<Video>) -> ExploreUiState
            ) = launch {
                var responded = false
                flow
                    .catch { e ->
                        Timber.e(e, "ExploreViewModel: Error cargando $what")
                        source.update { it.copy(error = errorFor(e)) }
                        if (!responded) {
                            responded = true
                            settle()
                        }
                    }
                    .collect { videos ->
                        source.update { apply(it, videos.filterNot { v -> v.userId in blockedUserIds }) }
                        if (!responded) {
                            responded = true
                            settle()
                        }
                    }
            }

            timeoutJob = launch {
                delay(LOAD_TIMEOUT_MS)
                if (pending > 0) {
                    Timber.e("ExploreViewModel: Timeout cargando Explorar")
                    source.update { it.copy(isLoading = false, error = it.error ?: R.string.explore_error_offline) }
                }
            }
            collectFlow("trending", videoRepository.getTopVideos()) { st, v -> st.copy(trending = v) }
            collectFlow("recientes", videoRepository.getTodayVideos(RECENT_LIMIT)) { st, v -> st.copy(recent = v) }
        }
    }

    private fun List<Video>.filterByQuery(needle: String): List<Video> {
        if (needle.isEmpty()) return this
        return filter {
            it.userName.contains(needle, ignoreCase = true) ||
                it.description.contains(needle, ignoreCase = true)
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 15_000L
        const val RECENT_LIMIT = 50
    }
}
