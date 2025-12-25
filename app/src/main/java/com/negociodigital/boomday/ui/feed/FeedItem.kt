package com.negociodigital.boomday.ui.feed

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.negociodigital.boomday.data.model.User

/**
 * Modelo de un post del Feed
 * Compatible con usuarios autenticados e invitados
 */
data class FeedItem(

    @PropertyName("id")
    val id: String = "",

    @PropertyName("user")
    val user: User = User.guest(), // ✅ NUNCA NULL

    @PropertyName("mediaUrl")
    val mediaUrl: String = "",

    @PropertyName("mediaType")
    val mediaType: String = "image", // image | video

    @PropertyName("thumbnailUrl")
    val thumbnailUrl: String = "",

    @PropertyName("description")
    val description: String = "",

    @PropertyName("likes")
    val likes: Int = 0,

    @PropertyName("comments")
    val comments: Int = 0,

    @PropertyName("views")
    val views: Int = 0,

    @PropertyName("shares")
    val shares: Int = 0,

    @PropertyName("hashtags")
    val hashtags: List<String> = emptyList(),

    @PropertyName("isLiked")
    val isLiked: Boolean = false,

    @PropertyName("duration")
    val duration: String = "",

    @PropertyName("createdAt")
    val createdAt: Timestamp = Timestamp.now()
) {

    /** 🔹 Computed */
    val isVideo: Boolean
        get() = mediaType == "video"

    val hoursAgo: Int
        get() {
            val now = System.currentTimeMillis()
            val postTime = createdAt.toDate().time
            return ((now - postTime) / (1000 * 60 * 60))
                .toInt()
                .coerceAtLeast(0)
        }

    fun getTimeAgoText(): String = when {
        hoursAgo < 1 -> "Ahora"
        hoursAgo == 1 -> "Hace 1 hora"
        hoursAgo < 24 -> "Hace $hoursAgo horas"
        hoursAgo < 48 -> "Ayer"
        hoursAgo < 168 -> "Hace ${hoursAgo / 24} días"
        hoursAgo < 720 -> "Hace ${hoursAgo / 168} semanas"
        hoursAgo < 8760 -> "Hace ${hoursAgo / 720} meses"
        else -> "Hace ${hoursAgo / 8760} años"
    }

    fun formatCount(value: Int): String = when {
        value >= 1_000_000 -> "${value / 1_000_000}M"
        value >= 1_000 -> "${value / 1_000}K"
        else -> value.toString()
    }
}
