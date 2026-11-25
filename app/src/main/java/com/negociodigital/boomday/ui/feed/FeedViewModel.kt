package com.negociodigital.boomday.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.AlreadyReportedException
import com.negociodigital.boomday.data.repository.ReportRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import com.negociodigital.boomday.ui.util.isOfflineError
import java.io.IOException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

internal const val BLOCKED_LOAD_TIMEOUT_MS = 15_000L
internal const val FEED_PAGE_SIZE = 15

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val userRepository: UserRepository,
    private val reportRepository: ReportRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _feedState = MutableStateFlow<FeedState>(FeedState.Loading)
    val feedState: StateFlow<FeedState> = _feedState.asStateFlow()

    private val blockedUserIds = MutableStateFlow<Set<String>>(emptySet())

    // Int es un @StringRes: la UI lo resuelve con stringResource() para no hardcodear
    // texto en el ViewModel.
    private val _actionMessage = MutableStateFlow<Int?>(null)
    val actionMessage: StateFlow<Int?> = _actionMessage.asStateFlow()

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    // videoIds ya notificados al repo en esta sesión del ViewModel, para no reenviar
    // incrementViews() en cada recomposición de la pantalla. Envuelto en
    // Collections.synchronizedSet: hoy onVideoViewed() siempre se llama confinado a
    // Dispatchers.Main (viewModelScope + LaunchedEffect de Compose comparten el mismo
    // hilo), pero nada en la firma del método garantiza eso a futuro — la sincronización
    // explícita no cuesta nada de performance aquí y evita que un futuro llamador desde
    // otro dispatcher introduzca una carrera silenciosa.
    private val notifiedViewIds = Collections.synchronizedSet(mutableSetOf<String>())

    private var feedJob: Job? = null
    private var autoRetried = false

    /**
     * ON_RESUME de la pestaña: si el feed está en Error reintenta UNA vez automáticamente
     * (la bandera se limpia al cargar con éxito), sin polling ni bucles.
     */
    fun onScreenResumed() {
        if (_feedState.value is FeedState.Error && !autoRetried) {
            autoRetried = true
            refreshFeed()
        }
    }

    init {
        Timber.d("FeedViewModel: Inicializando")
        loadFeed()
    }

    // Cursor opaco de la última página pedida (aunque quedara vacía tras filtrar bloqueados).
    private var cursor: Any? = null
    private var pageJob: Job? = null

    private fun loadFeed() {
        cursor = null
        feedJob = viewModelScope.launch {
            val uid = auth.currentUser?.uid
            if (uid != null) {
                // Fail-closed: sin la lista de bloqueados no se muestra el feed (reintento = refreshFeed).
                // withTimeoutOrNull: el timeout devuelve null; una cancelación real del scope se propaga.
                val result = withTimeoutOrNull(BLOCKED_LOAD_TIMEOUT_MS) { userRepository.getBlockedUsers(uid) }
                if (result == null) {
                    Timber.e("FeedViewModel: Timeout obteniendo usuarios bloqueados")
                    _feedState.value = FeedState.Error(R.string.feed_error_offline)
                    return@launch
                }
                val blocked = result.getOrElse { e ->
                    Timber.e(e, "FeedViewModel: Error obteniendo usuarios bloqueados")
                    _feedState.value = errorFor(e)
                    return@launch
                }
                blockedUserIds.value = blocked
            }
            fetchPages(emptyList()).fold(
                onSuccess = { (videos, end) -> publish(videos, end) },
                onFailure = { e ->
                    Timber.e(e, "FeedViewModel: Error cargando feed")
                    _feedState.value = errorFor(e)
                }
            )
        }
    }

    private fun errorFor(e: Throwable) = FeedState.Error(
        if (e.isOfflineError()) R.string.feed_error_offline else R.string.feed_error_generic
    )

    private fun publish(videos: List<Video>, end: Boolean) {
        autoRetried = false
        _feedState.value = if (videos.isEmpty()) {
            FeedState.Empty
        } else {
            FeedState.Success(videos, if (end) NextPageState.End else NextPageState.Idle)
        }
    }

    /**
     * Pide páginas hasta aportar al menos un video visible (no bloqueado, no repetido) o
     * llegar al final: una página puede quedar vacía tras filtrar bloqueados y, sin este
     * bucle, la UI no tendría nada que dispare la siguiente carga. Devuelve la lista
     * acumulada y si se llegó al final. Si una página falla, devuelve el fallo.
     */
    private suspend fun fetchPages(current: List<Video>): Result<Pair<List<Video>, Boolean>> {
        val seen = current.mapTo(HashSet()) { it.videoId }
        var acc = current
        while (true) {
            // Timeout por página: sin salida infinita si la red no responde ni falla. El cursor
            // solo avanza tras una página completa, así que un timeout no salta páginas.
            val page = (withTimeoutOrNull(BLOCKED_LOAD_TIMEOUT_MS) {
                videoRepository.getTodayVideosPage(cursor, FEED_PAGE_SIZE)
            } ?: Result.failure(IOException("timeout")))
                .getOrElse { return Result.failure(it) }
            cursor = page.nextCursor
            val blocked = blockedUserIds.value
            val fresh = page.videos.filter { it.userId !in blocked && seen.add(it.videoId) }
            acc = acc + fresh
            if (page.endReached) return Result.success(acc to true)
            if (fresh.isNotEmpty()) return Result.success(acc to false)
        }
    }

    /** Carga la siguiente página (también es el reintento tras un error de página). */
    fun loadNextPage() {
        val current = _feedState.value as? FeedState.Success ?: return
        if (current.nextPage == NextPageState.Loading || current.nextPage == NextPageState.End) return
        if (pageJob?.isActive == true) return
        _feedState.value = current.copy(nextPage = NextPageState.Loading)
        pageJob = viewModelScope.launch {
            fetchPages(current.videos).fold(
                onSuccess = { (videos, end) ->
                    _feedState.value = FeedState.Success(
                        videos, if (end) NextPageState.End else NextPageState.Idle
                    )
                },
                onFailure = { e ->
                    Timber.e(e, "FeedViewModel: Error cargando la siguiente página")
                    // Conserva lo ya cargado; solo la página siguiente queda en error.
                    _feedState.value = current.copy(nextPage = NextPageState.Error)
                }
            )
        }
    }

    /**
     * Refrescar descarta el cursor y recarga desde la primera página (las páginas son
     * lecturas únicas, sin listener). Mantiene la lista visible como Refreshing mientras
     * tanto; un fallo termina en Error (reintento = volver a llamar).
     */
    fun refreshFeed() {
        val current = _feedState.value
        // Idempotente: con una recarga ya en curso (Loading/Refreshing) no se relanza.
        if ((current is FeedState.Loading || current is FeedState.Refreshing) && feedJob?.isActive == true) return
        _feedState.value = when (current) {
            is FeedState.Success -> FeedState.Refreshing(current.videos)
            is FeedState.Refreshing -> FeedState.Refreshing(current.videos)
            else -> FeedState.Loading
        }
        pageJob?.cancel()
        feedJob?.cancel()
        loadFeed()
    }

    /**
     * Notifica una vista de video. Idempotente por sesión de ViewModel: si ya se notificó
     * este videoId, no hace nada (ni siquiera loguea, para no ensuciar logs con cada
     * recomposición).
     */
    fun onVideoViewed(videoId: String) {
        if (!notifiedViewIds.add(videoId)) {
            // Ya estaba en el set: add() devuelve false y no dispara nada más.
            return
        }

        viewModelScope.launch {
            val result = videoRepository.incrementViews(videoId)
            // Si falla, solo logueamos: no removemos el id del set. Decisión deliberada
            // para priorizar "no gastar red en cada recomposición" sobre un posible
            // undercount raro por fallo transitorio.
            result.onFailure { e ->
                Timber.e(e, "FeedViewModel: Error incrementando vista de video: $videoId")
            }
        }
    }

    /**
     * Bloquea a un usuario: se actualiza `blockedUsers` en Firestore y, de inmediato (sin
     * esperar a que el listener de Firestore vuelva a emitir), se refleja en el feed actual
     * porque `blockedUserIds` es la key del combine() de arriba.
     */
    fun blockUser(userId: String) {
        viewModelScope.launch {
            val currentUid = auth.currentUser?.uid ?: return@launch
            userRepository.blockUser(currentUid, userId)
                .onSuccess {
                    blockedUserIds.value = blockedUserIds.value + userId
                    applyBlockToLoaded(userId)
                    _actionMessage.value = R.string.feed_block_success_message
                }
                .onFailure { e ->
                    Timber.e(e, "FeedViewModel: Error bloqueando usuario: $userId")
                    _actionMessage.value = R.string.feed_block_error_message
                }
        }
    }

    private fun applyBlockToLoaded(userId: String) {
        val current = _feedState.value as? FeedState.Success ?: return
        val remaining = current.videos.filterNot { it.userId == userId }
        when {
            remaining.isNotEmpty() -> _feedState.value = current.copy(videos = remaining)
            current.nextPage == NextPageState.End -> _feedState.value = FeedState.Empty
            else -> {
                // Quedó vacío pero hay más páginas: se piden en vez de mostrar Empty falso.
                _feedState.value = current.copy(videos = remaining, nextPage = NextPageState.Idle)
                loadNextPage()
            }
        }
    }

    fun reportVideo(videoId: String, reportedUserId: String, reason: String) {
        viewModelScope.launch {
            reportRepository.submitReport(reportedUserId, videoId, reason)
                .onSuccess {
                    _actionMessage.value = R.string.feed_report_success_message
                }
                .onFailure { e ->
                    Timber.e(e, "FeedViewModel: Error enviando reporte de video: $videoId")
                    _actionMessage.value = when (e) {
                        is AlreadyReportedException -> R.string.feed_report_already_message
                        else -> R.string.feed_report_error_message
                    }
                }
        }
    }
}
