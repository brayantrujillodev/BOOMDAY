package com.negociodigital.boomday.data.model

import com.google.firebase.firestore.PropertyName

data class User(
    @PropertyName("uid")
    val uid: String = "",

    @PropertyName("email")
    val email: String = "",

    @PropertyName("name")
    val name: String = "",

    @PropertyName("avatar")
    val avatar: String? = null,

    @PropertyName("type")
    val type: String = "user", // "user" | "guest"

    @PropertyName("createdAt")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Crea un usuario invitado por defecto
         */
        fun guest(): User = User(
            uid = "",
            email = "",
            name = "Invitado",
            avatar = null,
            type = "guest"
        )
    }

    /**
     * Verifica si un usuario es invitado
     */
    fun isGuest(): Boolean = type == "guest" || uid.isEmpty()
}
