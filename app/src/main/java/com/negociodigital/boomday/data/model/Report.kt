package com.negociodigital.boomday.data.model

import com.google.firebase.firestore.PropertyName

/**
 * Reporte de contenido/usuario enviado por un usuario. Colección `reports` en Firestore:
 * solo creable por el cliente (nunca leído/editado/borrado desde la app — la revisión es
 * manual/administrativa), ver firestore.rules.
 */
data class Report(
    @PropertyName("reportId")
    val reportId: String = "",

    @PropertyName("reporterId")
    val reporterId: String = "",

    @PropertyName("reportedUserId")
    val reportedUserId: String = "",

    @PropertyName("reportedVideoId")
    val reportedVideoId: String = "",

    @PropertyName("reason")
    val reason: String = "",

    @PropertyName("createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
