package com.negociodigital.boomday.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.firebase.auth.FirebaseAuth
import com.negociodigital.boomday.data.auth.GoogleAuthConfig
import com.negociodigital.boomday.data.auth.GoogleAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuthTestActivity : ComponentActivity() {

    private lateinit var authRepository: GoogleAuthRepository
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar Firebase Auth y repositorio
        firebaseAuth = FirebaseAuth.getInstance()
        authRepository = GoogleAuthRepository(firebaseAuth)

        // Configurar la interfaz con Compose
        setContent {
            MaterialTheme {
                TestLoginScreen(
                    onLoginClick = {
                        Log.d("AUTH_TEST", "Botón de login presionado")
                        launchGoogleSignIn()
                    }
                )
            }
        }
    }

    /**
     * Launcher para el resultado de la actividad de inicio de sesión de Google
     */
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGoogleSignInResult(result.data)
    }

    /**
     * Inicia el flujo de autenticación con Google
     */
    private fun launchGoogleSignIn() {
        try {
            val signInClient = GoogleAuthConfig.getSignInClient(this)
            googleSignInLauncher.launch(signInClient.signInIntent)
        } catch (e: Exception) {
            Log.e("AUTH_TEST", "Error al iniciar Google Sign-In", e)
        }
    }


    /**
     * Maneja el resultado del inicio de sesión con Google
     * @param data Intent con los datos del resultado
     */
    private fun handleGoogleSignInResult(data: Intent?) {
        if (data == null) {
            Log.e("AUTH_TEST", "Datos de resultado nulos")
            return
        }

        try {
            // Obtener la cuenta de Google desde el intent
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.result

            // Verificar que tenemos un token válido
            val idToken = account.idToken
            if (idToken == null) {
                Log.e("AUTH_TEST", "Token ID nulo")
                return
            }

            // Autenticar con Firebase en un hilo secundario
            CoroutineScope(Dispatchers.IO).launch {
                val authResult = authRepository.signInWithGoogle(idToken)

                if (authResult.isSuccess) {
                    // Éxito: obtener usuario actual
                    val currentUser = firebaseAuth.currentUser
                    Log.d(
                        "AUTH_TEST",
                        "Autenticación exitosa. UID: ${currentUser?.uid ?: "null"}"
                    )

                    // Opcional: volver al hilo principal para actualizar UI
                    // withContext(Dispatchers.Main) { /* Actualizar UI */ }

                } else {
                    // Error: log del problema
                    val exception = authResult.exceptionOrNull()
                    Log.e("AUTH_TEST", "Error en autenticación Firebase", exception)
                }
            }

        } catch (e: Exception) {
            Log.e("AUTH_TEST", "Error en Google Sign-In", e)
        }
    }
}

/**
 * Pantalla de prueba para autenticación
 */
@Composable
fun TestLoginScreen(onLoginClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onLoginClick
        ) {
            Text("Iniciar sesión con Google (TEST)")
        }
    }
}