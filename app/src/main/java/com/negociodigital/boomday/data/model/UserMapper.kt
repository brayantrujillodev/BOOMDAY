package com.negociodigital.boomday.data.model

import com.google.firebase.Timestamp
import java.util.Date

/**
 * Mapeo puro (testeable en JVM) de los datos crudos de `users/{uid}` a [User].
 *
 * Existe porque `DocumentSnapshot.toObject(User::class.java)` NO convierte Timestamp a Long
 * (CustomClassMapper lanza "Failed to convert value of type Timestamp to Long"), y los
 * documentos creados por UserRepository.createOrUpdateUser guardan createdAt como Timestamp.
 * Este mapeo acepta Timestamp, Date, Number y ausente/otro tipo, sin lanzar.
 */
object UserMapper {

    /** Timestamp / Date / Number -> epoch millis; cualquier otra cosa (o null) -> null. */
    fun toEpochMillis(value: Any?): Long? = when (value) {
        is Timestamp -> value.toDate().time
        is Date -> value.time
        is Number -> value.toLong()
        else -> null
    }

    /** Lista de strings no vacíos; tolera ausencia, null, no-lista y elementos de otro tipo. */
    fun blockedUsersFrom(value: Any?): List<String> =
        (value as? List<*>)
            ?.filterIsInstance<String>()
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun fromMap(data: Map<String, Any?>, fallbackUid: String = ""): User = User(
        uid = (data["uid"] as? String)?.takeIf { it.isNotEmpty() } ?: fallbackUid,
        email = data["email"] as? String ?: "",
        name = data["name"] as? String ?: "",
        avatar = data["avatar"] as? String,
        type = data["type"] as? String ?: "user",
        createdAt = toEpochMillis(data["createdAt"]) ?: 0L,
        blockedUsers = blockedUsersFrom(data["blockedUsers"])
    )
}
