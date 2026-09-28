package com.negociodigital.boomday.ui.profile

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.auth.GoogleAuthConfig
import com.negociodigital.boomday.data.repository.GoogleAuthRepository
import com.negociodigital.boomday.data.repository.ProfileRepository
import com.negociodigital.boomday.domain.usecase.DeleteAccountResult
import com.negociodigital.boomday.domain.usecase.DeleteAccountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = true,
    val user: FirebaseUser? = null,
    val isLoggedOut: Boolean = false,
    val isDeleting: Boolean = false,
    val isAccountDeleted: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val googleAuthRepository: GoogleAuthRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    // Intent de Google Sign-In para reautenticar antes de borrar la cuenta. Mismo patrón
    // que LoginViewModel.signInIntent: la UI lo observa y lo lanza con un
    // ActivityResultLauncher.
    private val _reauthIntent = MutableStateFlow<Intent?>(null)
    val reauthIntent: StateFlow<Intent?> = _reauthIntent.asStateFlow()

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
                repository.signOut(context)
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

    /**
     * Inicia el borrado de la cuenta. Si Firebase exige reautenticación (sesión no
     * reciente), prepara un Intent de Google Sign-In: la UI debe observar
     * [reauthIntent] y lanzarlo, y llamar a [handleReauthResult] con el resultado.
     */
    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true, error = null)
            runDeleteAccount()
        }
    }

    private suspend fun runDeleteAccount() {
        when (val result = deleteAccountUseCase.execute()) {
            is DeleteAccountResult.Success -> {
                // La cuenta de Firebase Auth ya no existe; falta cerrar la sesión del
                // cliente de Google Sign-In para que no quede una cuenta "recordada".
                GoogleAuthConfig.signOut(context)
                _uiState.value = _uiState.value.copy(isDeleting = false, isAccountDeleted = true)
            }
            is DeleteAccountResult.NeedsReauth -> {
                Timber.d("🔐 [ProfileVM] Borrado requiere reautenticación, relanzando Google Sign-In")
                try {
                    _reauthIntent.value = GoogleAuthConfig.getSignInClient(context).signInIntent
                } catch (e: Exception) {
                    Timber.e(e, "❌ [ProfileVM] Error preparando el Intent de reautenticación")
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = "No se pudo iniciar la reautenticación. Verifica Google Play Services."
                    )
                }
            }
            is DeleteAccountResult.Error -> {
                _uiState.value = _uiState.value.copy(isDeleting = false, error = result.message)
            }
        }
    }

    fun clearReauthIntent() {
        _reauthIntent.value = null
    }

    /**
     * Procesa el resultado del Google Sign-In lanzado para reautenticar, y si fue
     * exitoso reintenta el borrado completo de la cuenta.
     */
    fun handleReauthResult(data: Intent?) {
        if (data == null) {
            _uiState.value = _uiState.value.copy(
                isDeleting = false,
                error = "Eliminación de cuenta cancelada"
            )
            return
        }

        viewModelScope.launch {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken

                if (idToken == null) {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = "No se pudo reautenticar"
                    )
                    return@launch
                }

                googleAuthRepository.reauthenticateWithGoogle(idToken)
                    .onSuccess { runDeleteAccount() }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(
                            isDeleting = false,
                            error = "No se pudo reautenticar: ${e.message}"
                        )
                    }
            } catch (e: ApiException) {
                Timber.w(e, "⚠️ [ProfileVM] Reautenticación cancelada por el usuario")
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    error = "Eliminación de cuenta cancelada"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
