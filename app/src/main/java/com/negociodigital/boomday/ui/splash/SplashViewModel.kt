package com.negociodigital.boomday.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negociodigital.boomday.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SplashState {
    data object Loading : SplashState
    data object LoggedIn : SplashState
    data object LoggedOut : SplashState
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SplashState>(SplashState.Loading)
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            delay(2500) // Tiempo de visualización del branding del splash
            _state.value = if (authRepository.isUserLoggedIn()) {
                SplashState.LoggedIn
            } else {
                SplashState.LoggedOut
            }
        }
    }
}
