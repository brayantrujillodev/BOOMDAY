package com.negociodigital.boomday.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.negociodigital.boomday.data.model.Report
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val reportsCollection = firestore.collection("reports")

    /**
     * Envía un reporte de contenido/usuario. reporterId se toma del usuario autenticado,
     * nunca de un parámetro del caller, para que coincida con lo que exige firestore.rules.
     */
    suspend fun submitReport(
        reportedUserId: String,
        reportedVideoId: String,
        reason: String
    ): Result<Unit> {
        return try {
            val reporterId = auth.currentUser?.uid
                ?: return Result.failure(IllegalStateException("Debes iniciar sesión para reportar"))

            val docRef = reportsCollection.document()
            val report = Report(
                reportId = docRef.id,
                reporterId = reporterId,
                reportedUserId = reportedUserId,
                reportedVideoId = reportedVideoId,
                reason = reason
            )
            docRef.set(report).await()

            Timber.d("ReportRepository: Reporte enviado: ${docRef.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "ReportRepository: Error enviando reporte")
            Result.failure(e)
        }
    }
}
