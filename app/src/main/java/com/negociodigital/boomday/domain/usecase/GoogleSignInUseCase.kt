package com.negociodigital.boomday.domain.usecase

import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.repository.GoogleAuthRepository
import com.negociodigital.boomday.data.repository.UserRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UseCase que coordina el flujo completo de Google Sign-In.
 *
 * Responsabilidad ÚNICA:
 * - Orquestar la autenticación con Google y la creación de usuario
 *
 * Flujo:
 * 1. Autentica con Firebase (GoogleAuthRepository)
 * 2. Crea/actualiza usuario en Firestore (UserRepository)
 * 3. Retorna resultado final
 *
 * Ventajas:
 * - GoogleAuthRepository y UserRepository NO se conocen entre sí
 * - LoginViewModel no necesita conocer 2 repositorios, solo este UseCase
 * - Fácil de testear (mockeas ambos repos por separado)
 * - Si agregas Facebook/Apple auth, creas otro UseCase similar
 */
@Singleton
class GoogleSignInUseCase @Inject constructor(
    private val googleAuthRepository: GoogleAuthRepository,
    private val userRepository: UserRepository
) {

    /**
     * Ejecuta el flujo completo de autenticación con Google.
     *
     * @param idToken Token de Google Sign-In obtenido del cliente
     * @return Result<Unit> - Success si todo el flujo fue exitoso, Failure con error específico
     */
    suspend fun execute(idToken: String): Result<Unit> {
        return try {
            Timber.d("🚀 [UseCase] Iniciando flujo de Google Sign-In")

            // PASO 1: Autenticar con Firebase usando Google
            val firebaseUser = googleAuthRepository.signInWithGoogle(idToken)
                .getOrElse { exception ->
                    Timber.e(exception, "❌ [UseCase] Error en autenticación con Firebase")
                    return Result.failure(exception)
                }

            Timber.d("✅ [UseCase] Usuario autenticado: ${firebaseUser.uid}")

            // PASO 2: Crear o actualizar usuario en Firestore
            userRepository.createOrUpdateUser(
                uid = firebaseUser.uid,
                email = firebaseUser.email ?: "",
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString()
            )

            Timber.i("✅ [UseCase] Flujo de Google Sign-In completado exitosamente")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ [UseCase] Error en flujo de Google Sign-In")
            Result.failure(e)
        }
    }

    /**
     * Verifica si hay un usuario autenticado actualmente.
     * Útil para decidir si mostrar Login o Home al abrir la app.
     *
     * @return true si hay sesión activa, false si no
     */
    fun isUserLoggedIn(): Boolean {
        return googleAuthRepository.getCurrentUserId() != null
    }

    /**
     * Obtiene el ID del usuario actual.
     *
     * @return UID del usuario o null si no hay sesión
     */
    fun getCurrentUserId(): String? {
        return googleAuthRepository.getCurrentUserId()
    }
}