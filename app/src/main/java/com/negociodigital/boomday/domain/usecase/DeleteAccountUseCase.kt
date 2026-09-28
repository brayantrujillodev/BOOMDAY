package com.negociodigital.boomday.domain.usecase

import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.negociodigital.boomday.data.repository.AuthRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resultado de [DeleteAccountUseCase.execute].
 */
sealed interface DeleteAccountResult {
    object Success : DeleteAccountResult
    /** Firebase exige un login reciente para borrar la cuenta; hay que reautenticar y reintentar. */
    object NeedsReauth : DeleteAccountResult
    data class Error(val message: String) : DeleteAccountResult
}

/**
 * UseCase que coordina el borrado completo de la cuenta de un usuario, requisito de
 * la política de Google Play para apps con creación de cuenta.
 *
 * Orden de las operaciones (importa, no es arbitrario):
 * 1. Videos del usuario (documento de Firestore + blobs de Storage) — mientras la
 *    sesión sigue activa, porque VideoRepository.deleteVideo() valida ownership contra
 *    auth.currentUser.
 * 2. Documento de perfil en users/{uid}.
 * 3. Cuenta de Firebase Auth — al final, porque una vez borrada, request.auth.uid deja
 *    de existir y las reglas de seguridad ya no dejarían borrar lo anterior.
 *
 * FIX (huérfanos permanentes): si el paso 1 o 2 falla (red inestable, timeout), el
 * flujo se detiene ahí y NUNCA llega a borrar la cuenta de Auth. Los UID de Firebase no
 * se reciclan, así que borrar la cuenta con un video sin borrar lo dejaría público y
 * huérfano para siempre — ningún usuario futuro podría volver a cumplir
 * `auth.uid == resource.data.userId` para reclamarlo y borrarlo.
 *
 * Los pasos 1 y 2 son idempotentes (StorageRepository.deleteFileByUrl y un documento
 * ya borrado no fallan si ya no existen), así que reintentar todo el flujo desde cero
 * después de un fallo (o de reautenticar por NeedsReauth) no duplica trabajo: lo ya
 * borrado simplemente no aparece de nuevo en getVideosByUser() o es un no-op en
 * deleteUser().
 */
@Singleton
class DeleteAccountUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val videoRepository: VideoRepository
) {

    suspend fun execute(): DeleteAccountResult {
        val uid = authRepository.getCurrentUserId()
            ?: return DeleteAccountResult.Error("No hay sesión activa")

        val videos = videoRepository.getVideosByUser(uid).getOrElse { e ->
            return DeleteAccountResult.Error(
                "No se pudieron obtener tus videos, intenta de nuevo: ${e.message}"
            )
        }

        // Se intenta borrar TODOS los videos (no se corta en el primer fallo) para
        // dejar el menor trabajo pendiente posible de cara a un reintento, pero si
        // alguno falló no se continúa: nunca se borra la cuenta de Auth con contenido
        // del usuario todavía sin borrar (ver comentario de la clase).
        val failedVideoIds = videos.mapNotNull { video ->
            val result = videoRepository.deleteVideo(video.videoId)
            result.exceptionOrNull()?.let { e ->
                Timber.e(e, "DeleteAccountUseCase: no se pudo borrar el video ${video.videoId}")
                video.videoId
            }
        }
        if (failedVideoIds.isNotEmpty()) {
            return DeleteAccountResult.Error(
                "No se pudieron borrar ${failedVideoIds.size} video(s). Revisa tu conexión e intenta de nuevo."
            )
        }

        userRepository.deleteUser(uid).onFailure { e ->
            Timber.e(e, "DeleteAccountUseCase: no se pudo borrar el documento de usuario $uid")
            return DeleteAccountResult.Error(
                "No se pudo borrar tu perfil, intenta de nuevo: ${e.message}"
            )
        }

        return authRepository.deleteCurrentUser().fold(
            onSuccess = {
                Timber.i("✅ [DeleteAccountUseCase] Cuenta eliminada por completo: $uid")
                DeleteAccountResult.Success
            },
            onFailure = { e ->
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    DeleteAccountResult.NeedsReauth
                } else {
                    // El contenido y el perfil ya se borraron; solo queda la cuenta de
                    // Auth. Es seguro reintentar (idempotente): un segundo toque en
                    // "Eliminar cuenta" vuelve a pasar por getVideosByUser()/deleteUser()
                    // como no-ops y solo repite este último paso.
                    DeleteAccountResult.Error(e.message ?: "No se pudo eliminar la cuenta")
                }
            }
        )
    }
}
