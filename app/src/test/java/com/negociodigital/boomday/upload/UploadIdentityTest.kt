package com.negociodigital.boomday.upload

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.StorageRepository
import com.negociodigital.boomday.data.repository.UploadRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** La identidad publicada sale de los claims del ID token, no de displayName/photoUrl locales. */
class UploadIdentityTest {

    private val storage = mockk<StorageRepository>()
    private val videos = mockk<VideoRepository>()
    private val auth = mockk<FirebaseAuth>()
    private val user = mockk<FirebaseUser>()
    private lateinit var repo: UploadRepository

    @Before
    fun setUp() {
        every { user.uid } returns "u1"
        every { user.displayName } returns "Nombre Local Distinto"
        every { user.photoUrl } returns null
        every { auth.currentUser } returns user
        repo = UploadRepository(storage, videos, auth, mockk(relaxed = true))
        repo.durationReader = { 10 }
        coEvery { storage.deleteFileByUrl(any()) } returns Result.success(Unit)
        every { storage.uploadVideoWithProgress(any(), any()) } returns flow { emit(UploadStatus.Success("https://blob")) }
    }

    private fun tokenWith(claims: Map<String, Any>) {
        val tokenResult = mockk<GetTokenResult> { every { this@mockk.claims } returns claims }
        val task = mockk<Task<GetTokenResult>> {
            every { isComplete } returns true
            every { isCanceled } returns false
            every { exception } returns null
            every { result } returns tokenResult
        }
        every { user.getIdToken(false) } returns task
    }

    private fun publish(): List<UploadStatus> = runBlocking {
        repo.uploadVideoComplete(mockk(), "t", "d", emptyList(), "vid-1").toList()
    }

    @Test
    fun videoCarriesExactTokenClaims_notLocalProfile() {
        tokenWith(mapOf("name" to "Ana Token", "picture" to "https://p/ana.jpg"))
        val sent = slot<Video>()
        coEvery { videos.saveVideo(capture(sent), any()) } returns Result.success("vid-1")
        publish()
        assertEquals("Ana Token", sent.captured.userName)
        assertEquals("https://p/ana.jpg", sent.captured.userAvatar)
    }

    @Test
    fun missingClaims_becomeEmptyStrings() {
        tokenWith(emptyMap())
        val sent = slot<Video>()
        coEvery { videos.saveVideo(capture(sent), any()) } returns Result.success("vid-1")
        publish()
        assertEquals("", sent.captured.userName)
        assertEquals("", sent.captured.userAvatar)
    }

    @Test
    fun tokenFailure_mapsToIdentityUnavailable_andDoesNotSave() {
        every { user.getIdToken(false) } throws IOException("offline")
        val last = publish().last() as UploadStatus.Error
        assertTrue(last.throwable is UploadException.IdentityUnavailable)
        coVerify(exactly = 0) { videos.saveVideo(any<Video>(), any()) }
        coVerify(exactly = 1) { storage.deleteFileByUrl("https://blob") }
    }

    @Test
    fun tokenNeverCompletes_timesOutInsteadOfFreezing() {
        repo.identityTimeoutMs = 100
        val pending = mockk<Task<GetTokenResult>>(relaxed = true) { every { isComplete } returns false }
        every { user.getIdToken(false) } returns pending
        val t0 = System.currentTimeMillis()
        val last = publish().last() as UploadStatus.Error
        assertTrue(last.throwable is UploadException.IdentityUnavailable)
        assertTrue("tardó demasiado", System.currentTimeMillis() - t0 < 5_000)
        coVerify(exactly = 0) { videos.saveVideo(any<Video>(), any()) }
    }
}
