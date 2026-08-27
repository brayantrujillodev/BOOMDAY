package com.negociodigital.boomday.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

data class Video(
    @PropertyName("videoId")
    val videoId: String = "",

    @PropertyName("userId")
    val userId: String = "",

    @PropertyName("userName")
    val userName: String = "",

    @PropertyName("userAvatar")
    val userAvatar: String = "",

    @PropertyName("videoUrl")
    val videoUrl: String = "",

    @PropertyName("thumbnailUrl")
    val thumbnailUrl: String = "",

    @PropertyName("title")
    val title: String = "",

    @PropertyName("description")
    val description: String = "",

    @PropertyName("duration")
    val duration: Int = 0, // en segundos

    @PropertyName("views")
    val views: Int = 0,

    @PropertyName("likes")
    val likes: Int = 0,

    @PropertyName("comments")
    val comments: Int = 0,

    @PropertyName("shares")
    val shares: Int = 0,

    @PropertyName("hashtags")
    val hashtags: List<String> = emptyList(),

    @PropertyName("createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @PropertyName("updatedAt")
    val updatedAt: Timestamp = Timestamp.now(),

    // Día calendario (zona horaria America/Bogota) en que se publicó el video, formato ISO
    // "yyyy-MM-dd". Calculado siempre con currentDayKeyBogota() (data/util/DateUtils.kt) —
    // es la clave de igualdad que permite a VideoRepository.getTopVideos() combinar
    // whereEqualTo + orderBy(views) sin chocar con la restricción de Firestore sobre
    // filtros de desigualdad + orderBy en campos distintos.
    @PropertyName("dayKey")
    val dayKey: String = ""
)