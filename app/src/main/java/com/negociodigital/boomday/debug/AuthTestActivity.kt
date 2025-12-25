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
import com.google.firebase.firestore.FirebaseFirestore
import com.negociodigital.boomday.data.auth.GoogleAuthConfig
import com.negociodigital.boomday.data.repository.GoogleAuthRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.domain.usecase.GoogleSignInUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Activity de prueba para validar el flujo de autenticación con Google.
 *
 * IMPORTANTE: Esta activity NO usa Hilt, crea las dependencias manualmente.
 * En la app real, LoginViewModel recibe todo inyectado por Hilt.
 */
class AuthTestActivity : ComponentActivity() {

    private lateinit var googleSignInUseCase: GoogleSignInUseCase
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar Firebase
        firebaseAuth = FirebaseAuth.getInstance()
        val firestore = FirebaseFirestore.getInstance()

        // ✅ Crear dependencias manualmente (sin Hilt)
        val googleAuthRepository = GoogleAuthRepository(firebaseAuth)
        val userRepository = UserRepository(firestore)

        // ✅ Crear el UseCase con ambos repositorios
        googleSignInUseCase = GoogleSignInUseCase(
            googleAuthRepository = googleAuthRepository,
            userRepository = userRepository
        )

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
            Log.d("AUTH_TEST", "Intent de Google Sign-In lanzado")
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

            Log.d("AUTH_TEST", "Cuenta de Google obtenida: ${account.email}")

            // Verificar que tenemos un token válido
            val idToken = account.idToken
            if (idToken == null) {
                Log.e("AUTH_TEST", "Token ID nulo")
                return
            }

            Log.d("AUTH_TEST", "Token ID obtenido, iniciando flujo completo...")

            // ✅ Autenticar con Firebase en un hilo secundario usando el UseCase
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // ✅ CAMBIO: Usa el UseCase que coordina todo
                    val authResult = googleSignInUseCase.execute(idToken)

                    if (authResult.isSuccess) {
                        // Éxito: obtener usuario actual
                        val currentUser = firebaseAuth.currentUser
                        Log.d(
                            "AUTH_TEST",
                            "✅ Autenticación exitosa!\n" +
                                    "UID: ${currentUser?.uid}\n" +
                                    "Email: ${currentUser?.email}\n" +
                                    "Nombre: ${currentUser?.displayName}\n" +
                                    "✅ Usuario creado/actualizado en Firestore"
                        )

                        // Opcional: Cerrar esta activity y volver a la app
                        // finish()

                    } else {
                        // Error: log del problema
                        val exception = authResult.exceptionOrNull()
                        Log.e("AUTH_TEST", "❌ Error en flujo de autenticación", exception)
                    }
                } catch (e: Exception) {
                    Log.e("AUTH_TEST", "❌ Excepción durante autenticación", e)
                }
            }

        } catch (e: Exception) {
            Log.e("AUTH_TEST", "❌ Error procesando resultado de Google Sign-In", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AUTH_TEST", "AuthTestActivity destruida")
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
            Text("🔐 Iniciar sesión con Google (TEST)")
        }
    }
}