package com.negociodigital.boomday.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

data class User(
    val uid: String = "",
    val email: String = "",

    @PropertyName("displayName")
    val name: String? = null,

    val avatar: String? = null,

    @PropertyName("createdAt")
    val createdAt: Timestamp? = null,

    @PropertyName("updatedAt")
    val updatedAt: Timestamp? = null,

    val type: String = "free",
    val points: Int = 0,
    val level: Int = 1
)