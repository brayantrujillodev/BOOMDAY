package com.negociodigital.boomday.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** El usuario ya reportó este video (o ya no está disponible): las reglas rechazan el segundo set. */
class AlreadyReportedException : Exception("Ya reportaste este video")

/** El video ya no existe (expiró o fue retirado) y no se puede reportar. */
class ReportedVideoUnavailableException : Exception("El video ya no está disponible")

/** Catálogo cerrado de motivos; debe coincidir con la lista de firestore.rules (B3). */
object ReportReasons {
    const val SPAM = "spam"
    const val HARASSMENT = "acoso"
    const val SEXUAL = "contenido_sexual"
    const val VIOLENCE = "violencia"
    const val ILLEGAL = "ilegal"
    const val OTHER = "otro"
    val ALL: List<String> = listOf(SPAM, HARASSMENT, SEXUAL, VIOLENCE, ILLEGAL, OTHER)
}

@Singleton
class ReportRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val reportsCollection = firestore.collection("reports")

    /**
     * Envía un reporte de un video. reporterId se toma SIEMPRE del usuario autenticado. El id del
     * documento es determinista (`<reporterId>_<videoId>`): las reglas son create-only, así que un
     * segundo reporte del mismo usuario sobre el mismo video falla con PERMISSION_DENIED y se
     * traduce a [AlreadyReportedException]. La evidencia (url, título, fecha, autor) se copia del
     * documento del video y las reglas la validan contra ese mismo documento.
     */
    suspend fun submitReport(
        reportedUserId: String,
        reportedVideoId: String,
        reason: String,
        details: String? = null
    ): Result<Unit> {
        return try {
            val reporterId = auth.currentUser?.uid
                ?: return Result.failure(IllegalStateException("Debes iniciar sesión para reportar"))
            if (reason !in ReportReasons.ALL) {
                return Result.failure(IllegalArgumentException("Motivo de reporte no válido: $reason"))
            }

            val video = firestore.collection("videos").document(reportedVideoId).get().await()
            if (!video.exists()) return Result.failure(ReportedVideoUnavailableException())

            val docId = reportDocId(reporterId, reportedVideoId)
            val data = buildReportData(
                reporterId = reporterId,
                reportedUserId = video.getString("userId") ?: reportedUserId,
                reportedVideoId = reportedVideoId,
                reason = reason,
                details = details,
                video = video
            )
            reportsCollection.document(docId).set(data).await()

            Timber.d("ReportRepository: Reporte enviado: $docId")
            Result.success(Unit)
        } catch (e: Exception) {
            if (isPermissionDenied(e)) {
                // No se puede distinguir "duplicado" de "video cambiado/eliminado": ambos son
                // rechazos de las reglas y el reporte ya existente es el caso abrumadoramente común.
                Timber.w(e, "ReportRepository: reporte rechazado por reglas (probable duplicado)")
                Result.failure(AlreadyReportedException())
            } else {
                Timber.e(e, "ReportRepository: Error enviando reporte")
                Result.failure(e)
            }
        }
    }

    // Seam de prueba: FirebaseFirestoreException.Code no se puede inicializar en la JVM sin Robolectric.
    internal var isPermissionDenied: (Exception) -> Boolean = { e ->
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    companion object {
        /** Id determinista del reporte: un solo reporte por (reportante, video). */
        fun reportDocId(reporterId: String, videoId: String): String = "${reporterId}_$videoId"

        /** Documento exactamente con las claves que permiten las reglas (sin campos de moderación). */
        fun buildReportData(
            reporterId: String,
            reportedUserId: String,
            reportedVideoId: String,
            reason: String,
            details: String?,
            video: DocumentSnapshot,
            nowMillis: Long = System.currentTimeMillis()
        ): Map<String, Any> {
            val data = hashMapOf<String, Any>(
                "reportId" to reportDocId(reporterId, reportedVideoId),
                "reporterId" to reporterId,
                "reportedUserId" to reportedUserId,
                "reportedVideoId" to reportedVideoId,
                "reason" to reason,
                "status" to "pending",
                "createdAt" to nowMillis,
                "evidence" to mapOf(
                    "videoUrl" to (video.getString("videoUrl") ?: ""),
                    "title" to (video.getString("title") ?: ""),
                    "videoCreatedAt" to (video.getLong("createdAt") ?: 0L),
                    "authorName" to (video.getString("userName") ?: "")
                )
            )
            details?.trim()?.takeIf { it.isNotEmpty() }?.let { data["details"] = it.take(300) }
            return data
        }
    }
}
