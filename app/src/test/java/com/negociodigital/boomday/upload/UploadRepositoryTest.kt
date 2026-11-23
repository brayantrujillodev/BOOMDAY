package com.negociodigital.boomday.upload

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class UploadRepositoryTest {

    private val uri = mockk<Uri>()
    private val storage = mockk<StorageRepository>()
    private val videos = mockk<VideoRepository>()
    private val auth = mockk<FirebaseAuth>()
    private val user = mockk<FirebaseUser>()
    private lateinit var repo: UploadRepository

    @Before
    fun setUp() {
        every { user.uid } returns "u1"
        every { user.displayName } returns "Ana"
        every { user.photoUrl } returns null
        every { auth.currentUser } returns user
        repo = UploadRepository(storage, videos, auth, mockk(relaxed = true))
        repo.durationReader = { 10 }
        coEvery { storage.deleteFileByUrl(any()) } returns Result.success(Unit)
    }

    private fun run(): Flow<UploadStatus> = repo.uploadVideoComplete(uri, "t", "d", emptyList(), "vid-1")

    private fun storageEmits(vararg s: UploadStatus) {
        every { storage.uploadVideoWithProgress(any(), any()) } returns flow { s.forEach { emit(it) } }
    }

    @Test
    fun nullUserEmitsNotLoggedInError() = runBlocking {
        every { auth.currentUser } returns null
        val e = run().toList().single() as UploadStatus.Error
        assertTrue(e.throwable is UploadException.NotLoggedIn)
    }

    @Test
    fun storageErrorIsMappedToErrorWithoutThrowing() = runBlocking {
        storageEmits(UploadStatus.Progress(10), UploadStatus.Error(IOException("x")))
        assertTrue(run().toList().last() is UploadStatus.Error)
    }

    @Test
    fun storageFlowThatThrowsIsCaughtAsError() = runBlocking {
        every { storage.uploadVideoWithProgress(any(), any()) } returns flow { throw IOException("boom") }
        assertTrue((run().toList().last() as UploadStatus.Error).throwable is IOException)
    }

    @Test
    fun storageCancelledIsCancelledNotError() = runBlocking {
        storageEmits(UploadStatus.Cancelled)
        assertEquals(listOf<UploadStatus>(UploadStatus.Cancelled), run().toList())
    }

    @Test
    fun collectorCancellationIsNotConvertedToError() = runBlocking {
        every { storage.uploadVideoWithProgress(any(), any()) } returns flow {
            emit(UploadStatus.Progress(5))
            awaitCancellation()
        }
        val seen = java.util.Collections.synchronizedList(mutableListOf<UploadStatus>())
        val job = launch(Dispatchers.Default) { run().collect { seen += it } }
        while (seen.isEmpty()) delay(10)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("No debe emitir Error al cancelar: $seen", seen.none { it is UploadStatus.Error })
    }

    @Test
    fun flowWithoutTerminalStatusEmitsIncompleteError() = runBlocking {
        storageEmits(UploadStatus.Progress(50))
        val last = run().toList().last() as UploadStatus.Error
        assertTrue(last.throwable is UploadException.Incomplete)
    }

    @Test
    fun invalidDurationEmitsError() = runBlocking {
        repo.durationReader = { 120 }
        val e = run().toList().single() as UploadStatus.Error
        assertTrue(e.throwable is UploadException.InvalidDuration)
    }

    @Test
    fun unreadableDurationEmitsError() = runBlocking {
        repo.durationReader = { throw IllegalStateException("x") }
        val e = run().toList().single() as UploadStatus.Error
        assertTrue(e.throwable is UploadException.CannotReadVideo)
    }

    @Test
    fun saveVideoFailureEmitsErrorAndDeletesOrphanBlob() = runBlocking {
        storageEmits(UploadStatus.Success("https://blob"))
        coEvery { videos.saveVideo(any<Video>(), any()) } returns Result.failure(IOException("firestore"))
        assertTrue(run().toList().last() is UploadStatus.Error)
        coVerify(exactly = 1) { storage.deleteFileByUrl("https://blob") }
    }

    @Test
    fun saveVideoTimeoutEmitsTimeoutAndKeepsBlob() = runBlocking {
        repo.saveTimeoutMs = 50
        storageEmits(UploadStatus.Success("https://blob"))
        coEvery { videos.saveVideo(any<Video>(), any()) } coAnswers { delay(10_000); Result.success("x") }
        val last = run().toList().last() as UploadStatus.Error
        assertTrue(last.throwable is UploadException.Timeout)
        coVerify(exactly = 0) { storage.deleteFileByUrl(any()) }
    }

    @Test
    fun saveVideoThrowingIsCaughtAsError() = runBlocking {
        storageEmits(UploadStatus.Success("https://blob"))
        coEvery { videos.saveVideo(any<Video>(), any()) } throws IllegalStateException("npe")
        assertTrue(run().toList().last() is UploadStatus.Error)
    }

    @Test
    fun successUsesProvidedVideoIdForIdempotentWrite() = runBlocking {
        storageEmits(UploadStatus.Success("https://blob"))
        coEvery { videos.saveVideo(any<Video>(), "vid-1") } returns Result.success("vid-1")
        assertEquals(UploadStatus.Success("vid-1"), run().toList().last())
        coVerify(exactly = 1) { videos.saveVideo(any<Video>(), "vid-1") }
    }

    @Test
    fun retryWithAlreadyPublishedDocIsSuccessAndDeletesNewBlob() = runBlocking {
        storageEmits(UploadStatus.Success("https://blob-nuevo"))
        coEvery { videos.saveVideo(any<Video>(), "vid-1") } returns Result.failure(UploadException.AlreadyPublished("vid-1"))
        assertEquals(UploadStatus.Success("vid-1"), run().toList().last())
        coVerify(exactly = 1) { storage.deleteFileByUrl("https://blob-nuevo") }
    }

    @Test
    fun idConflictIsErrorAndDeletesBlob() = runBlocking {
        storageEmits(UploadStatus.Success("https://blob-nuevo"))
        coEvery { videos.saveVideo(any<Video>(), "vid-1") } returns Result.failure(UploadException.IdConflict("vid-1"))
        assertTrue(run().toList().last() is UploadStatus.Error)
    }
}
