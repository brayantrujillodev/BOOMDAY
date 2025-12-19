package com.negociodigital.boomday.uy.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.negociodigital.boomday.data.auth.GoogleAuthConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor() : ViewModel() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Guarda el Intent para lanzarlo desde el Composable
    private val _signInIntent = MutableStateFlow<Intent?>(null)
    val signInIntent: StateFlow<Intent?> = _signInIntent.asStateFlow()

    fun prepareGoogleSignIn(context: Context) {
        try {
            _isLoading.value = true
            _errorMessage.value = null
            val client = GoogleAuthConfig.getSignInClient(context)
            _signInIntent.value = client.signInIntent
        } catch (e: Exception) {
            Log.e("LoginViewModel", "Error preparando Google Sign-In", e)
            _errorMessage.value = "Error al iniciar sesión"
            _isLoading.value = false
        }
    }

    fun clearSignInIntent() {
        _signInIntent.value = null
    }

    fun handleGoogleSignInResult(data: Intent?) {
        if (data == null) {
            _errorMessage.value = "Error en la autenticación"
            _isLoading.value = false
            return
        }

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)

            val idToken = account.idToken
            if (idToken != null) {
                firebaseAuthWithGoogle(idToken)
            } else {
                _errorMessage.value = "No se pudo obtener el token"
                _isLoading.value = false
            }

        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                10 -> "Error de configuración (SHA / Firebase)"
                12501, 12502 -> "Inicio de sesión cancelado"
                7 -> "Error de conexión"
                else -> "Error al iniciar sesión con Google (${e.statusCode})"
            }
            _errorMessage.value = message
            _isLoading.value = false
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = withTimeout(15_000) {
                    auth.signInWithCredential(credential).await()
                }

                val user = result.user
                if (user != null) {
                    registerUserInFirestore(user)
                }

                withContext(Dispatchers.Main) {
                    _isLoggedIn.value = true
                    _isLoading.value = false
                }

            } catch (e: TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Tiempo de espera agotado"
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Firebase auth error", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error al autenticar: ${e.message}"
                    _isLoading.value = false
                }
            }
        }
    }

    private suspend fun registerUserInFirestore(user: FirebaseUser) {
        try {
            val ref = db.collection("users").document(user.uid)
            val snapshot = ref.get().await()

            if (!snapshot.exists()) {
                val data = hashMapOf(
                    "uid" to user.uid,
                    "email" to (user.email ?: ""),
                    "displayName" to user.displayName,
                    "avatar" to user.photoUrl?.toString(),
                    "type" to "free",
                    "createdAt" to Timestamp.now(),
                    "updatedAt" to Timestamp.now()
                )
                ref.set(data).await()
                Log.d("LoginViewModel", "✅ Usuario registrado en Firestore")
            } else {
                Log.d("LoginViewModel", "✅ Usuario ya existe en Firestore")
            }
        } catch (e: Exception) {
            Log.e("LoginViewModel", "❌ Error Firestore", e)
        }
    }

    fun resetLoginState() {
        _isLoggedIn.value = false
    }
}