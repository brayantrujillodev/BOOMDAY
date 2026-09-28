package com.negociodigital.boomday.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio especializado en autenticación con Google Sign-In.
 *
 * Responsabilidad ÚNICA:
 * - Autenticar usuarios con Firebase usando credenciales de Google
 * - Validar estado de autenticación de Google
 * - Obtener tokens de Google para APIs
 *
 * NO maneja:
 * - Creación de usuarios en Firestore (lo hace UserRepository vía UseCase)
 * - Cierre de sesión global (lo hace AuthRepository)
 *
 * Escalabilidad:
 * Si necesitas Facebook/Apple auth → creas repositorios separados
 * FacebookAuthRepository, AppleAuthRepository, etc.
 */
@Singleton
class GoogleAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {

    /**
     * Autentica un usuario usando su token de Google Sign-In.
     *
     * Flujo:
     * 1. Crea credenciales de Google con el idToken
     * 2. Autentica con Firebase usando esas credenciales
     * 3. Retorna el FirebaseUser autenticado
     *
     * @param idToken Token de identificación obtenido de Google Sign-In
     * @return Result<FirebaseUser> - FirebaseUser si fue exitoso, Failure con excepción si hubo error
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            Timber.d("🔐 [GoogleAuthRepo] Iniciando autenticación con Firebase")

            // 1. Crear credenciales de Google
            val credential = GoogleAuthProvider.getCredential(idToken, null)

            // 2. Autenticar con Firebase
            val authResult = firebaseAuth.signInWithCredential(credential).await()

            // 3. Validar que recibimos un usuario
            val firebaseUser = authResult.user
                ?: throw IllegalStateException("Usuario nulo después de autenticación")

            Timber.i("✅ [GoogleAuthRepo] Firebase Auth exitoso: ${firebaseUser.email}")
            Result.success(firebaseUser)

        } catch (e: Exception) {
            Timber.e(e, "❌ [GoogleAuthRepo] Error en autenticación con Google")
            Result.failure(e)
        }
    }

    /**
     * Reautentica al usuario actual con una credencial de Google fresca.
     *
     * Firebase exige esto antes de operaciones sensibles (como borrar la cuenta) si
     * la sesión no es "reciente" — ver AuthRepository.deleteCurrentUser().
     *
     * @param idToken Token de identificación obtenido de un Google Sign-In recién hecho
     */
    suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(IllegalStateException("No hay usuario autenticado"))

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            user.reauthenticate(credential).await()

            Timber.i("✅ [GoogleAuthRepo] Reautenticación exitosa")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ [GoogleAuthRepo] Error en reautenticación")
            Result.failure(e)
        }
    }

    /**
     * Verifica si el usuario actual se autenticó usando Google.
     * Útil para mostrar UI específica de Google o validar permisos.
     *
     * @return true si el usuario usó Google Sign-In, false en caso contrario
     */
    fun isGoogleSignedIn(): Boolean {
        val user = firebaseAuth.currentUser ?: return false
        return user.providerData.any {
            it.providerId == GoogleAuthProvider.PROVIDER_ID
        }
    }

    /**
     * Obtiene el ID token actual de Google.
     * Útil si necesitas llamar APIs de Google (Drive, Calendar, etc.)
     *
     * @param forceRefresh Si true, obtiene un token nuevo aunque haya uno en caché
     * @return Token de Google o null si hay error o no hay usuario
     */
    suspend fun getGoogleIdToken(forceRefresh: Boolean = false): String? {
        return try {
            firebaseAuth.currentUser
                ?.getIdToken(forceRefresh)
                ?.await()
                ?.token
        } catch (e: Exception) {
            Timber.e(e, "❌ [GoogleAuthRepo] Error obteniendo ID token de Google")
            null
        }
    }

    /**
     * Verifica si hay un usuario autenticado actualmente.
     * @return true si hay sesión activa, false si no
     */
    fun isUserLoggedIn(): Boolean {
        return firebaseAuth.currentUser != null
    }

    /**
     * Obtiene el UID del usuario actual.
     * @return UID o null si no hay usuario autenticado
     */
    fun getCurrentUserId(): String? {
        return firebaseAuth.currentUser?.uid
    }

    /**
     * Obtiene el email del usuario actual.
     * @return Email o null si no hay usuario o no tiene email
     */
    fun getCurrentUserEmail(): String? {
        return firebaseAuth.currentUser?.email
    }

    /**
     * Obtiene el FirebaseUser actual.
     * @return FirebaseUser o null si no hay sesión
     */
    fun getCurrentUser(): FirebaseUser? {
        return firebaseAuth.currentUser
    }
}