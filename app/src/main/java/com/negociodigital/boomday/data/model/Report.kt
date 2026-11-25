package com.negociodigital.boomday.data.model

import com.google.firebase.firestore.PropertyName

/**
 * Reporte de un video. Colección `reports`, id `<reporterId>_<videoId>` (ver firestore.rules y
 * docs/ETAPA_B3_MODERACION.md). El cliente solo CREA reportes (con ReportRepository.buildReportData);
 * leer y moderar es exclusivo de moderadores/backend, por eso esta clase no se usa para escribir.
 */
data class Report(
    @PropertyName("reportId") val reportId: String = "",
    @PropertyName("reporterId") val reporterId: String = "",
    @PropertyName("reportedUserId") val reportedUserId: String = "",
    @PropertyName("reportedVideoId") val reportedVideoId: String = "",
    @PropertyName("reason") val reason: String = "",
    @PropertyName("details") val details: String? = null,
    // pending -> in_review -> resolved (resolution: dismissed | content_removed | user_actioned)
    @PropertyName("status") val status: String = "pending",
    @PropertyName("createdAt") val createdAt: Long = System.currentTimeMillis()
)
