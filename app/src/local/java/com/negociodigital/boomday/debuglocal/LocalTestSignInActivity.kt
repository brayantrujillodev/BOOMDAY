package com.negociodigital.boomday.debuglocal

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.negociodigital.boomday.LocalEnvironment
import com.negociodigital.boomday.MainActivity
import com.negociodigital.boomday.data.repository.UserRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Login de pruebas REAL contra Auth Emulator (solo build type "local").
 * adb shell am start -n com.negociodigital.boomday.local/com.negociodigital.boomday.debuglocal.LocalTestSignInActivity
 *   --es email alice@boomday.test --es password Test1234! [--es name Alice]
 */
@AndroidEntryPoint
class LocalTestSignInActivity : ComponentActivity() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var userRepository: UserRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val email = intent.getStringExtra("email")?.trim().orEmpty()
        val password = intent.getStringExtra("password").orEmpty()
        val name = intent.getStringExtra("name")

        if (!LocalEnvironment.emulatorActive) return abort("LocalEnvironment no esta en modo emulador")
        if (!email.endsWith("@boomday.test", ignoreCase = true)) return abort("email debe ser @boomday.test")
        if (password.length < 6) return abort("password requerido (min 6)")

        lifecycleScope.launch {
            try {
                val user = try {
                    auth.createUserWithEmailAndPassword(email, password).await().user
                } catch (e: FirebaseAuthUserCollisionException) {
                    auth.signInWithEmailAndPassword(email, password).await().user
                }
                if (user == null) {
                    abort("sin usuario tras auth")
                    return@launch
                }

                val display = name ?: email.substringBefore('@')
                user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(display).build()).await()

                // Mismo camino que el login real (GoogleSignInUseCase)
                userRepository.createOrUpdateUser(user.uid, email, display, null)
                Log.i(TAG, "LOCAL SIGN-IN OK uid=${user.uid} email=$email")
                startActivity(Intent(this@LocalTestSignInActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                abort("fallo: ${e.message}")
            }
        }
    }

    private fun abort(msg: String) {
        Log.e(TAG, "LOCAL SIGN-IN ABORT: $msg")
        Toast.makeText(this, "LocalTestSignIn: $msg", Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val TAG = "LocalTestSignIn"
    }
}
