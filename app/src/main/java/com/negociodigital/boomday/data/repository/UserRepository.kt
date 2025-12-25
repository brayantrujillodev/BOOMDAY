package com.negociodigital.boomday.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.negociodigital.boomday.data.model.User
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio que maneja todas las operaciones relacionadas con usuarios en Firestore.
 * Responsabilidades:
 * - Crear y actualizar usuarios
 * - Consultar información de usuarios
 * - Verificar existencia de usuarios
 */
@Singleton
class UserRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    // Constante para la colección de usuarios
    private companion object {
        const val USERS_COLLECTION = "users"
    }

    /**
     * Obtiene un usuario desde Firestore por su UID.
     * @param uid ID único del usuario
     * @return User si existe, null si no existe o hay error
     */
    suspend fun getUser(uid: String): User? {
        return try {
            val document = firestore
                .collection(USERS_COLLECTION)
                .document(uid)
                .get()
                .await()

            if (document.exists()) {
                document.toObject(User::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Crea un nuevo usuario en Firestore.
     * @param user Objeto User a crear
     * @return true si se creó exitosamente, false si hubo error
     */
    suspend fun createUser(user: User): Boolean {
        return try {
            firestore
                .collection(USERS_COLLECTION)
                .document(user.uid)
                .set(user)
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifica si un usuario existe en Firestore.
     * @param uid ID único del usuario
     * @return true si existe, false si no existe o hay error
     */
    suspend fun userExists(uid: String): Boolean {
        return try {
            val document = firestore
                .collection(USERS_COLLECTION)
                .document(uid)
                .get()
                .await()
            document.exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Crea o actualiza un usuario después del login con Google.
     * Si el usuario NO existe: lo crea con todos los datos
     * Si el usuario YA existe: solo actualiza la fecha de última actualización
     *
     * @param uid ID único del usuario de Firebase Auth
     * @param email Email del usuario
     * @param displayName Nombre para mostrar (puede ser null)
     * @param photoUrl URL de la foto de perfil (puede ser null)
     */
    suspend fun createOrUpdateUser(
        uid: String,
        email: String,
        displayName: String?,
        photoUrl: String?
    ) {
        try {
            val userRef = firestore
                .collection(USERS_COLLECTION)
                .document(uid)

            // Verificamos si el usuario ya existe
            val snapshot = userRef.get().await()

            if (!snapshot.exists()) {
                // Usuario nuevo - Creamos el documento completo
                val userData = hashMapOf(
                    "uid" to uid,
                    "email" to email,
                    "displayName" to displayName,
                    "avatar" to photoUrl,
                    "type" to "free", // Tipo por defecto
                    "createdAt" to Timestamp.now(),
                    "updatedAt" to Timestamp.now()
                )
                userRef.set(userData).await()
            } else {
                // Usuario existente - Solo actualizamos la fecha
                userRef.update(
                    mapOf("updatedAt" to Timestamp.now())
                ).await()
            }
        } catch (e: Exception) {
            // Propagamos el error para que GoogleAuthRepository lo maneje
            throw e
        }
    }
}