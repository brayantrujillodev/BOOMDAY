package com.negociodigital.boomday.ui.feed

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.negociodigital.boomday.data.model.User

data class StoryModel(
    @PropertyName("id")
    val id: String = "",

    @PropertyName("user")
    val user: User = User.guest(),

    @PropertyName("mediaUrl")
    val mediaUrl: String = "",

    @PropertyName("mediaType")
    val mediaType: String = "image",

    @PropertyName("thumbnailUrl")
    val thumbnailUrl: String = "",

    @PropertyName("isViewed")
    val isViewed: Boolean = false,

    @PropertyName("duration")
    val duration: Int = 5,

    @PropertyName("createdAt")
    val createdAt: Timestamp = Timestamp.now()
)