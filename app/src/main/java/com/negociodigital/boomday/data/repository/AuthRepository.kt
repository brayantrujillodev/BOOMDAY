package com.negociodigital.boomday.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio central para gestión de autenticación.
 *
 * Responsabilidad ÚNICA:
 * - Gestionar el ESTADO de autenticación (sesión activa/inactiva)
 * - Proveer información del usuario autenticado de Firebase Auth
 * - Cerrar sesión
 * - Observar cambios en el estado de autenticación
 *
 * NO maneja:
 * - Datos del usuario en Firestore (eso es UserRepository)
 * - Login con proveedores específicos (eso es GoogleAuthRepository, etc.)
 *
 * Analogía:
 * AuthRepository = "Recepcionista del hotel"
 * - Sabe quién está registrado (logged in)
 * - Hace check-out (logout)
 * - NO maneja las habitaciones (datos en Firestore)
 */
@Singleton
class AuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {

    // ==================== ESTADO DE SESIÓN ====================

    /**
     * Verifica si hay un usuario autenticado actualmente.
     * @return true si hay sesión activa, false si no
     */
    fun isUserLoggedIn(): Boolean {
        return firebaseAuth.currentUser != null
    }

    /**
     * Obtiene el usuario actualmente autenticado de Firebase Auth.
     * @return FirebaseUser si hay sesión, null si no hay usuario autenticado
     */
    fun getCurrentUser(): FirebaseUser? {
        return firebaseAuth.currentUser
    }

    /**
     * Obtiene el UID del usuario actual.
     * @return UID del usuario o null si no hay sesión activa
     */
    fun getCurrentUserId(): String? {
        return firebaseAuth.currentUser?.uid
    }

    /**
     * Obtiene el email del usuario actual.
     * @return Email o null si no hay sesión o el usuario no tiene email
     */
    fun getCurrentUserEmail(): String? {
        return firebaseAuth.currentUser?.email
    }

    /**
     * Obtiene el nombre para mostrar del usuario actual.
     * @return Display name o null si no está configurado
     */
    fun getCurrentUserDisplayName(): String? {
        return firebaseAuth.currentUser?.displayName
    }

    /**
     * Obtiene la URL de la foto de perfil del usuario actual.
     * @return URL de la foto o null si no tiene
     */
    fun getCurrentUserPhotoUrl(): String? {
        return firebaseAuth.currentUser?.photoUrl?.toString()
    }

    // ==================== OBSERVABLES ====================

    /**
     * Flow que emite el estado de autenticación en tiempo real.
     *
     * Útil para:
     * - Redirigir al login cuando se cierra sesión
     * - Actualizar UI cuando cambia el estado de autenticación
     * - Implementar lógica reactiva basada en sesión
     *
     * @return Flow<FirebaseUser?> - Emite el usuario actual o null si no hay sesión
     */
    fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val authStateListener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser)
        }

        firebaseAuth.addAuthStateListener(authStateListener)

        awaitClose {
            firebaseAuth.removeAuthStateListener(authStateListener)
        }
    }

    // ==================== ACCIONES ====================

    /**
     * Cierra la sesión del usuario actual.
     *
     * Esto:
     * - Cierra sesión en Firebase Auth
     * - Limpia el token de autenticación local
     * - Dispara el AuthStateListener notificando el cambio
     *
     * Importante: NO borra datos de Firestore, solo cierra la sesión.
     */
    fun signOut() {
        try {
            firebaseAuth.signOut()
            Timber.i("🚪 Sesión cerrada exitosamente")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error al cerrar sesión")
            // No lanzamos excepción porque signOut debe ser siempre exitoso
        }
    }

    /**
     * Verifica si el email del usuario actual está verificado.
     * @return true si está verificado, false si no o si no hay usuario
     */
    fun isEmailVerified(): Boolean {
        return firebaseAuth.currentUser?.isEmailVerified == true
    }

    /**
     * Verifica si el usuario es anónimo.
     * @return true si es usuario anónimo, false en caso contrario
     */
    fun isAnonymous(): Boolean {
        return firebaseAuth.currentUser?.isAnonymous == true
    }

    /**
     * Obtiene la lista de proveedores de autenticación del usuario actual.
     * Ejemplo: ["google.com", "password"]
     *
     * @return Lista de IDs de proveedores o lista vacía si no hay usuario
     */
    fun getProviderIds(): List<String> {
        return firebaseAuth.currentUser?.providerData
            ?.map { it.providerId }
            ?.filter { it != "firebase" } // Filtrar el proveedor base de Firebase
            ?: emptyList()
    }
}