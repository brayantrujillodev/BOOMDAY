package com.negociodigital.boomday.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negociodigital.boomday.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class FeedState {
    LOADING,
    REFRESHING,
    SUCCESS,
    EMPTY,
    ERROR
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _feedState = MutableStateFlow(FeedState.LOADING)
    val feedState: StateFlow<FeedState> = _feedState.asStateFlow()

    init {
        Timber.d("FeedViewModel: Inicializando")
        loadFeed()
    }

    private fun loadFeed() {
        viewModelScope.launch {
            try {
                _feedState.value = FeedState.LOADING
                // TODO: Cargar datos
                _feedState.value = FeedState.SUCCESS
            } catch (e: Exception) {
                Timber.e(e, "Error")
                _feedState.value = FeedState.ERROR
            }
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            try {
                _feedState.value = FeedState.REFRESHING
                // TODO: Recargar datos
                _feedState.value = FeedState.SUCCESS
            } catch (e: Exception) {
                _feedState.value = FeedState.ERROR
            }
        }
    }

    fun resetFeed() {
        _feedState.value = FeedState.LOADING
        loadFeed()
    }
}