package com.negociodigital.boomday.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.repository.ReportRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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

    init {
        Timber.d("FeedViewModel: Inicializando")
        loadFeed()
    }

    private fun loadFeed() {
        feedJob = viewModelScope.launch {
            auth.currentUser?.uid?.let { uid ->
                blockedUserIds.value = userRepository.getBlockedUsers(uid)
            }
            combine(videoRepository.getTodayVideos(), blockedUserIds) { videos, blocked ->
                videos.filterNot { it.userId in blocked }
            }
                .catch { e ->
                    Timber.e(e, "FeedViewModel: Error cargando feed")
                    _feedState.value = FeedState.Error(e.message ?: "Error al cargar el feed")
                }
                .collect { videos ->
                    _feedState.value = if (videos.isEmpty()) {
                        FeedState.Empty
                    } else {
                        FeedState.Success(videos)
                    }
                }
        }
    }

    /**
     * Decisión de diseño: aunque getTodayVideos() es un snapshot listener que ya empuja
     * cambios en tiempo real (en teoría no haría falta volver a suscribirse), depender solo
     * de eso dejaría el gesto de "refrescar" atascado en Refreshing indefinidamente si no
     * hay ningún cambio real en el servidor entre medio (el listener simplemente no vuelve
     * a emitir). Por eso refreshFeed() SÍ cancela la suscripción activa y vuelve a
     * suscribirse: Firestore entrega la primera emisión de un listener nuevo casi al
     * instante desde caché local, así que el usuario ve la transición de Refreshing a
     * Success/Empty acotada en el tiempo en vez de un estado que puede no resolverse nunca.
     */
    fun refreshFeed() {
        val current = _feedState.value
        _feedState.value = when (current) {
            is FeedState.Success -> FeedState.Refreshing(current.videos)
            is FeedState.Refreshing -> FeedState.Refreshing(current.videos)
            else -> FeedState.Loading
        }
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
                    _actionMessage.value = R.string.feed_block_success_message
                }
                .onFailure { e ->
                    Timber.e(e, "FeedViewModel: Error bloqueando usuario: $userId")
                    _actionMessage.value = R.string.feed_block_error_message
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
                    _actionMessage.value = R.string.feed_report_error_message
                }
        }
    }
}
