package com.negociodigital.boomday.data.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReportRepositoryTest {

    private val reportDoc = mockk<DocumentReference>()
    private val videoDoc = mockk<DocumentReference>()
    private val auth = mockk<FirebaseAuth>()
    private lateinit var repo: ReportRepository

    private fun <T> done(value: T?, error: Exception? = null): Task<T> = mockk<Task<T>>().also {
        every { it.isComplete } returns true
        every { it.isCanceled } returns false
        every { it.exception } returns error
        if (error == null) every { it.result } returns value
    }

    private fun videoSnap(exists: Boolean = true) = mockk<DocumentSnapshot>().also {
        every { it.exists() } returns exists
        every { it.getString("userId") } returns "owner"
        every { it.getString("videoUrl") } returns "https://firebasestorage.googleapis.com/x"
        every { it.getString("title") } returns "titulo"
        every { it.getString("userName") } returns "Autor"
        every { it.getLong("createdAt") } returns 1700000000000L
    }

    @Before
    fun setUp() {
        val reports = mockk<CollectionReference>()
        val videos = mockk<CollectionReference>()
        val fs = mockk<FirebaseFirestore>()
        every { fs.collection("reports") } returns reports
        every { fs.collection("videos") } returns videos
        every { videos.document("v1") } returns videoDoc
        every { videoDoc.get() } returns done(videoSnap())
        every { auth.currentUser } returns mockk<FirebaseUser> { every { uid } returns "alice" }
        every { reports.document(any()) } returns reportDoc
        every { reportDoc.set(any()) } returns done<Void>(null)
        repo = ReportRepository(fs, auth)
    }

    @Test
    fun deterministicId_isReporterUnderscoreVideo() {
        assertEquals("alice_v1", ReportRepository.reportDocId("alice", "v1"))
    }

    @Test
    fun submit_usesAuthUidAndDeterministicIdWithExactAllowedKeys() = runBlocking {
        val captured = slot<Any>()
        every { reportDoc.set(capture(captured)) } returns done<Void>(null)
        val fs2 = mockk<FirebaseFirestore>()
        val reports = mockk<CollectionReference>()
        every { fs2.collection("reports") } returns reports
        every { fs2.collection("videos") } returns mockk { every { document("v1") } returns videoDoc }
        every { reports.document("alice_v1") } returns reportDoc
        val r = ReportRepository(fs2, auth)

        // el caller intenta colar otro "reportedUserId": se usa el autor real del video
        assertTrue(r.submitReport("impostor", "v1", ReportReasons.SPAM, "  detalle  ").isSuccess)

        verify { reports.document("alice_v1") }
        @Suppress("UNCHECKED_CAST")
        val data = captured.captured as Map<String, Any>
        assertEquals(
            setOf("reportId", "reporterId", "reportedUserId", "reportedVideoId", "reason", "details", "status", "createdAt", "evidence"),
            data.keys
        )
        assertEquals("alice", data["reporterId"])
        assertEquals("alice_v1", data["reportId"])
        assertEquals("owner", data["reportedUserId"])
        assertEquals("pending", data["status"])
        assertEquals("detalle", data["details"])
        assertEquals(setOf("videoUrl", "title", "videoCreatedAt", "authorName"), (data["evidence"] as Map<*, *>).keys)
    }

    @Test
    fun noDetails_omitsTheKey() {
        val data = ReportRepository.buildReportData("alice", "owner", "v1", "otro", "   ", videoSnap())
        assertFalse(data.containsKey("details"))
    }

    @Test
    fun noSession_failsWithoutTouchingFirestore() = runBlocking {
        every { auth.currentUser } returns null
        assertTrue(repo.submitReport("owner", "v1", ReportReasons.SPAM).isFailure)
        verify(exactly = 0) { reportDoc.set(any()) }
    }

    @Test
    fun reasonOutsideCatalog_isRejectedLocally() = runBlocking {
        val res = repo.submitReport("owner", "v1", "inappropriate")
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
        verify(exactly = 0) { reportDoc.set(any()) }
    }

    @Test
    fun missingVideo_isUnavailable() = runBlocking {
        every { videoDoc.get() } returns done(videoSnap(exists = false))
        assertTrue(repo.submitReport("owner", "v1", ReportReasons.SPAM).exceptionOrNull() is ReportedVideoUnavailableException)
    }

    @Test
    fun permissionDenied_isAlreadyReported_noCrash() = runBlocking {
        val denied = IllegalStateException("PERMISSION_DENIED")
        repo.isPermissionDenied = { it === denied }
        every { reportDoc.set(any()) } returns done<Void>(null, denied)
        assertTrue(repo.submitReport("owner", "v1", ReportReasons.SPAM).exceptionOrNull() is AlreadyReportedException)
    }

    @Test
    fun otherErrors_propagateAsFailure() = runBlocking {
        val unavailable = java.io.IOException("offline")
        repo.isPermissionDenied = { false }
        every { reportDoc.set(any()) } returns done<Void>(null, unavailable)
        val e = repo.submitReport("owner", "v1", ReportReasons.SPAM).exceptionOrNull()
        assertTrue(e === unavailable)
    }

    @Test
    fun catalogMatchesRulesCodes() {
        assertEquals(
            listOf("spam", "acoso", "contenido_sexual", "violencia", "ilegal", "otro"),
            ReportReasons.ALL
        )
    }
}
