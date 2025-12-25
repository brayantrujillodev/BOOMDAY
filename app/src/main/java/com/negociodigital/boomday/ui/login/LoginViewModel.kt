package com.negociodigital.boomday.ui.login

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.negociodigital.boomday.data.auth.GoogleAuthConfig
import com.negociodigital.boomday.domain.usecase.GoogleSignInUseCase
import com.negociodigital.boomday.ui.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel para la pantalla de Login.
 *
 * Responsabilidades:
 * - Coordinar el flujo de Google Sign-In
 * - Manejar el estado de la UI (loading, error, success)
 * - Procesar resultados de autenticación
 * - Comunicar eventos a la UI
 *
 * NO hace:
 * - Lógica de autenticación (lo hace GoogleSignInUseCase)
 * - Operaciones de Firebase directas
 * - Manipulación de datos en Firestore
 *
 * Flujo:
 * 1. Usuario presiona botón → prepareGoogleSignIn()
 * 2. UI lanza el intent → handleGoogleSignInResult()
 * 3. ViewModel coordina con GoogleSignInUseCase
 * 4. UI reacciona al cambio de estado
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val googleSignInUseCase: GoogleSignInUseCase // ✅ CAMBIO: UseCase en lugar de Repository
) : ViewModel() {

    // ==================== ESTADO UNIFICADO ====================

    private val _state = MutableStateFlow(AuthState())
    val state = _state.asStateFlow()

    private val _signInIntent = MutableStateFlow<Intent?>(null)
    val signInIntent = _signInIntent.asStateFlow()

    // ==================== COMPATIBILIDAD CON UI ANTIGUA ====================
    // Estos flows mantienen compatibilidad con LoginScreen si usa los flows por separado
    // Puedes eliminarlos una vez migres LoginScreen a usar solo "state"

    val isLoading = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)
    val isLoggedIn = MutableStateFlow(false)

    init {
        // Sincroniza el estado unificado con los flows individuales
        viewModelScope.launch {
            state.collect { authState ->
                isLoading.value = authState.isLoading
                errorMessage.value = authState.error
                isLoggedIn.value = authState.isLoggedIn
            }
        }
    }

    // ==================== PREPARACIÓN DE SIGN-IN ====================

    /**
     * Prepara el Intent de Google Sign-In.
     *
     * La UI debe observar signInIntent y cuando cambie (no sea null),
     * lanzar el intent usando ActivityResultLauncher.
     *
     * @param context Contexto de Android necesario para GoogleAuthConfig
     */
    fun prepareGoogleSignIn(context: Context) {
        try {
            Timber.d("🔐 [LoginVM] Preparando Google Sign-In")
            val client = GoogleAuthConfig.getSignInClient(context)
            _signInIntent.value = client.signInIntent
        } catch (e: Exception) {
            Timber.e(e, "❌ [LoginVM] Error preparando Google Sign-In")
            _state.value = _state.value.copy(
                error = "Error al iniciar Google Sign-In. Verifica tu configuración."
            )
        }
    }

    /**
     * Limpia el Intent de Sign-In después de ser usado.
     * Debe llamarse después de lanzar el intent para evitar relanzamientos.
     */
    fun clearSignInIntent() {
        _signInIntent.value = null
    }

    // ==================== PROCESAMIENTO DE RESULTADO ====================

    /**
     * Procesa el resultado del Google Sign-In Activity.
     *
     * @param data Intent con el resultado de Google Sign-In, o null si fue cancelado
     */
    fun handleGoogleSignInResult(data: Intent?) {
        // Validar que hay datos
        if (data == null) {
            Timber.w("⚠️ [LoginVM] Login cancelado por el usuario")
            _state.value = _state.value.copy(
                error = "Inicio de sesión cancelado"
            )
            return
        }

        try {
            // Extraer la cuenta de Google del resultado
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            // Validar que obtuvimos el token
            val idToken = account.idToken
            if (idToken != null) {
                Timber.d("✅ [LoginVM] Token de Google obtenido exitosamente")
                authenticateWithGoogle(idToken)
            } else {
                Timber.e("❌ [LoginVM] Token de Google es nulo")
                _state.value = _state.value.copy(
                    error = "No se pudo obtener el token de autenticación"
                )
            }

        } catch (e: ApiException) {
            // Manejar errores específicos de Google Sign-In
            val errorMessage = when (e.statusCode) {
                10 -> {
                    Timber.e("❌ [LoginVM] Error de configuración de Google Sign-In")
                    "Error de configuración (SHA-1 o Firebase). Contacta al desarrollador."
                }
                12501, 12502 -> {
                    Timber.w("⚠️ [LoginVM] Usuario canceló el login")
                    "Inicio de sesión cancelado"
                }
                7 -> {
                    Timber.e("❌ [LoginVM] Error de conexión")
                    "Error de conexión. Verifica tu internet."
                }
                else -> {
                    Timber.e(e, "❌ [LoginVM] Error desconocido en Google Sign-In: ${e.statusCode}")
                    "Error al iniciar sesión. Código: ${e.statusCode}"
                }
            }

            _state.value = _state.value.copy(error = errorMessage)
        }
    }

    // ==================== AUTENTICACIÓN ====================

    /**
     * Ejecuta el proceso de autenticación con Google.
     * Coordina con GoogleSignInUseCase y actualiza el estado según el resultado.
     *
     * @param idToken Token de Google obtenido del sign-in
     */
    private fun authenticateWithGoogle(idToken: String) {
        viewModelScope.launch {
            // Activar estado de carga
            _state.value = _state.value.copy(
                isLoading = true,
                error = null
            )

            Timber.d("🔄 [LoginVM] Iniciando flujo de autenticación completo...")

            // ✅ CAMBIO: Ejecutar autenticación usando el UseCase
            val result = googleSignInUseCase.execute(idToken)

            // Actualizar estado según resultado
            if (result.isSuccess) {
                Timber.i("✅ [LoginVM] Autenticación exitosa")
                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    error = null
                )
            } else {
                val exception = result.exceptionOrNull()
                Timber.e(exception, "❌ [LoginVM] Error en autenticación")
                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoggedIn = false,
                    error = exception?.message ?: "Error desconocido al autenticar"
                )
            }
        }
    }

    // ==================== ACCIONES DE UI ====================

    /**
     * Reinicia el estado del login.
     * Útil cuando el usuario regresa a la pantalla de login después de logout.
     */
    fun resetLoginState() {
        Timber.d("🔄 [LoginVM] Reiniciando estado de login")
        _state.value = AuthState()
    }

    /**
     * Limpia el mensaje de error.
     * Útil si quieres que el error desaparezca después de mostrarlo.
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}