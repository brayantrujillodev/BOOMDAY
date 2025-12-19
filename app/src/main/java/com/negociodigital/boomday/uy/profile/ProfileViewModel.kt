package com.negociodigital.boomday.uy.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoading: Boolean = true,
    val user: FirebaseUser? = null,
    val isLoggedOut: Boolean = false,
    val error: String? = null
)

class ProfileViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = ProfileRepository()

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    init {
        loadUser()
    }

    private fun loadUser() {
        val user = repository.getCurrentUser()
        _uiState.value = ProfileUiState(
            isLoading = false,
            user = user
        )
    }

    fun logout() {
        viewModelScope.launch {
            try {
                repository.signOut(getApplication())
                _uiState.value = _uiState.value.copy(
                    isLoggedOut = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message
                )
            }
        }
    }
}
