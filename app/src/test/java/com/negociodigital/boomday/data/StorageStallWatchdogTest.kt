package com.negociodigital.boomday.data

import android.net.Uri
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.repository.StallWatchdog
import com.negociodigital.boomday.data.repository.StorageRepository
import com.negociodigital.boomday.data.repository.storagePathFromDownloadUrl
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageStallWatchdogTest {

    private val timeout = 45_000L

    // ---------- StallWatchdog puro ----------

    @Test
    fun watchdogFiresAfterTimeoutWithZeroBytesFromStart() = runTest {
        var fired = 0
        val w = StallWatchdog(this, timeout) { fired++ }
        w.start()
        advanceTimeBy(timeout - 1)
        assertEquals(0, fired)
        advanceTimeBy(2)
        assertEquals(1, fired)
    }

    @Test
    fun growingBytesKeepResettingSoTotalDurationNeverExpires() = runTest {
        var fired = 0
        val w = StallWatchdog(this, timeout) { fired++ }
        w.start()
        var bytes = 0L
        repeat(10) { // 10 x 30 s = 300 s totales, muy por encima de 45 s
            advanceTimeBy(30_000)
            bytes += 1
            w.onProgress(bytes)
        }
        assertEquals(0, fired)
        w.stop()
    }

    @Test
    fun repeatedSameBytesDoNotResetTheCounter() = runTest {
        var fired = 0
        val w = StallWatchdog(this, timeout) { fired++ }
        w.start()
        advanceTimeBy(1_000)
        w.onProgress(100) // aumenta: deadline = 1_000 + 45_000
        advanceTimeBy(30_000)
        w.onProgress(100) // repetido: NO reinicia
        advanceTimeBy(10_000)
        w.onProgress(100) // repetido: NO reinicia
        advanceTimeBy(4_999) // t = 45_999 -> aun no
        assertEquals(0, fired)
        advanceTimeBy(2) // t = 46_001 -> expiro (si repetidos reiniciaran, no habria expirado)
        assertEquals(1, fired)
    }

    @Test
    fun zeroBytesEventsDoNotResetTheCounter() = runTest {
        var fired = 0
        val w = StallWatchdog(this, timeout) { fired++ }
        w.start()
        advanceTimeBy(40_000)
        w.onProgress(0)
        advanceTimeBy(5_001)
        assertEquals(1, fired)
    }

    @Test
    fun stopPreventsFiring() = runTest {
        var fired = 0
        val w = StallWatchdog(this, timeout) { fired++ }
        w.start()
        w.stop()
        advanceTimeBy(200_000)
        assertEquals(0, fired)
    }

    // ---------- Integracion con UploadTask mockeado ----------

    private class Harness(val repo: StorageRepository, val task: UploadTask, val child: StorageReference) {
        val progress = slot<OnProgressListener<UploadTask.TaskSnapshot>>()
        val success = slot<OnSuccessListener<UploadTask.TaskSnapshot>>()
        val failure = slot<OnFailureListener>()
        val emitted = mutableListOf<UploadStatus>()
    }

    private fun harness(): Harness {
        val user = mockk<FirebaseUser>().also { every { it.uid } returns "u1" }
        val auth = mockk<FirebaseAuth>().also { every { it.currentUser } returns user }
        val storage = mockk<FirebaseStorage>()
        val root = mockk<StorageReference>()
        val child = mockk<StorageReference>()
        val task = mockk<UploadTask>(relaxed = true)
        every { storage.reference } returns root
        every { root.child(any()) } returns child
        every { child.putFile(any(), any()) } returns task
        val h = Harness(StorageRepository(storage, auth), task, child)
        every { task.addOnProgressListener(capture(h.progress)) } returns task
        every { task.addOnSuccessListener(capture(h.success)) } returns task
        every { task.addOnFailureListener(capture(h.failure)) } returns task
        every { task.isComplete } returns false
        return h
    }

    private fun canceledException(): StorageException = mockk<StorageException>().also {
        every { it.errorCode } returns StorageException.ERROR_CANCELED
    }

    private fun snapshot(bytes: Long) = mockk<UploadTask.TaskSnapshot>().also {
        every { it.bytesTransferred } returns bytes
        every { it.totalByteCount } returns 1_000L
    }

    private fun TestScope.collectIn(h: Harness): Job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
            h.repo.uploadVideoWithProgress(mockk<Uri>(), null, timeout).collect { h.emitted += it }
        }

    @Test
    fun noProgressFor45sEmitsTimeoutErrorNotCancelledAndCancelsTaskAndRemovesListeners() = runTest {
        val h = harness()
        // El SDK real entrega ERROR_CANCELED al failureListener tras task.cancel().
        every { h.task.cancel() } answers {
            h.failure.captured.onFailure(canceledException())
            true
        }
        val job = collectIn(h)

        advanceTimeBy(timeout - 1)
        assertTrue(h.emitted.isEmpty())
        advanceTimeBy(2)

        assertEquals("Solo un terminal: $${h.emitted}", 1, h.emitted.size)
        val err = h.emitted.single() as UploadStatus.Error
        assertTrue(err.throwable is UploadException.Timeout)
        assertFalse(h.emitted.any { it is UploadStatus.Cancelled })
        verify(atLeast = 1) { h.task.cancel() }
        verify { h.task.removeOnProgressListener(h.progress.captured) }
        verify { h.task.removeOnSuccessListener(h.success.captured) }
        verify { h.task.removeOnFailureListener(h.failure.captured) }
        assertTrue(job.isCompleted)
    }

    @Test
    fun progressSnapshotDeliveredByCancelAfterStallIsNotEmitted() = runTest {
        val h = harness()
        every { h.task.cancel() } answers {
            h.progress.captured.onProgress(snapshot(230))
            h.failure.captured.onFailure(canceledException())
            true
        }
        val job = collectIn(h)
        h.progress.captured.onProgress(snapshot(230))
        advanceTimeBy(timeout + 1)
        assertEquals(listOf<Class<*>>(UploadStatus.Progress::class.java, UploadStatus.Error::class.java), h.emitted.map { it::class.java })
        assertTrue((h.emitted.last() as UploadStatus.Error).throwable is UploadException.Timeout)
        assertTrue(job.isCompleted)
    }

    @Test
    fun successCompletingExactlyAtWatchdogExpiryWinsAndNoTimeoutIsEmitted() = runTest {
        val h = harness()
        val url = mockk<Uri>().also { every { it.toString() } returns "https://x/y" }
        // Al expirar el watchdog la transferencia ya termino (callback de exito en camino).
        every { h.task.isComplete } returns true
        every { h.child.downloadUrl } returns com.google.android.gms.tasks.Tasks.forResult(url)
        val job = collectIn(h)
        advanceTimeBy(timeout + 1)
        assertTrue("el watchdog no debe emitir Timeout", h.emitted.isEmpty())
        h.success.captured.onSuccess(mockk(relaxed = true))
        advanceTimeBy(1_000)
        assertEquals(listOf<UploadStatus>(UploadStatus.Success("https://x/y")), h.emitted)
        verify(exactly = 0) { h.task.cancel() }
        job.join()
    }

    @Test
    fun successAfterTimeoutClaimedIsIgnoredAndTimeoutStaysTerminal() = runTest {
        val h = harness()
        val job = collectIn(h)
        advanceTimeBy(timeout + 1)
        h.success.captured.onSuccess(mockk(relaxed = true))
        advanceTimeBy(1_000)
        assertEquals(1, h.emitted.size)
        assertTrue((h.emitted.single() as UploadStatus.Error).throwable is UploadException.Timeout)
        job.join()
    }

    @Test
    fun failureAfterSuccessClaimedDoesNotOverwriteSuccess() = runTest {
        val h = harness()
        val url = mockk<Uri>().also { every { it.toString() } returns "https://x/y" }
        // downloadUrl aun pendiente cuando llega el fallo tardio: el canal sigue abierto.
        val pending = com.google.android.gms.tasks.TaskCompletionSource<Uri>()
        every { h.child.downloadUrl } returns pending.task
        val job = collectIn(h)
        h.success.captured.onSuccess(mockk(relaxed = true))
        h.failure.captured.onFailure(canceledException())
        advanceTimeBy(timeout * 3)
        pending.setResult(url)
        advanceTimeBy(1_000)
        assertEquals(listOf<UploadStatus>(UploadStatus.Success("https://x/y")), h.emitted)
        job.join()
    }

    @Test
    fun growingProgressBeyond45sTotalNeverExpires() = runTest {
        val h = harness()
        val job = collectIn(h)
        repeat(6) { i -> // 180 s totales
            advanceTimeBy(30_000)
            h.progress.captured.onProgress(snapshot((i + 1) * 100L))
        }
        assertTrue(h.emitted.none { it is UploadStatus.Error })
        assertTrue(h.emitted.all { it is UploadStatus.Progress })
        assertEquals(6, h.emitted.size)
        verify(exactly = 0) { h.task.cancel() }
        job.cancel()
    }

    @Test
    fun repeatedSameBytesDoNotRestartTimerInRepository() = runTest {
        val h = harness()
        val job = collectIn(h)
        advanceTimeBy(1_000)
        h.progress.captured.onProgress(snapshot(100))
        advanceTimeBy(30_000)
        h.progress.captured.onProgress(snapshot(100))
        advanceTimeBy(10_000)
        h.progress.captured.onProgress(snapshot(100))
        advanceTimeBy(4_999)
        assertTrue(h.emitted.none { it is UploadStatus.Error })
        advanceTimeBy(2)
        assertTrue(h.emitted.last() is UploadStatus.Error)
        job.cancel()
    }

    @Test
    fun voluntaryCancelIsCancelledAndWatchdogStops() = runTest {
        val h = harness()
        val job = collectIn(h)
        advanceTimeBy(10_000)
        h.failure.captured.onFailure(canceledException())
        advanceTimeBy(200_000)
        assertEquals(listOf<UploadStatus>(UploadStatus.Cancelled), h.emitted)
        assertTrue(job.isCompleted)
    }

    @Test
    fun genericFailureIsErrorAndWatchdogStops() = runTest {
        val h = harness()
        val job = collectIn(h)
        h.failure.captured.onFailure(java.io.IOException("x"))
        advanceTimeBy(200_000)
        assertEquals(1, h.emitted.size)
        assertTrue((h.emitted.single() as UploadStatus.Error).throwable is java.io.IOException)
        assertTrue(job.isCompleted)
    }

    @Test
    fun successStillWorksAndWatchdogDoesNotFireAfterwards() = runTest {
        val user = mockk<FirebaseUser>().also { every { it.uid } returns "u1" }
        val auth = mockk<FirebaseAuth>().also { every { it.currentUser } returns user }
        val storage = mockk<FirebaseStorage>()
        val root = mockk<StorageReference>()
        val child = mockk<StorageReference>()
        val task = mockk<UploadTask>(relaxed = true)
        val success = slot<OnSuccessListener<UploadTask.TaskSnapshot>>()
        every { storage.reference } returns root
        every { root.child(any()) } returns child
        every { child.putFile(any(), any()) } returns task
        every { task.addOnSuccessListener(capture(success)) } returns task
        every { task.isComplete } returns true
        every { child.downloadUrl } returns com.google.android.gms.tasks.Tasks.forResult(
            mockk<Uri>().also { every { it.toString() } returns "https://x/y" }
        )
        val emitted = mutableListOf<UploadStatus>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StorageRepository(storage, auth).uploadVideoWithProgress(mockk<Uri>(), null, timeout)
                .collect { emitted += it }
        }
        advanceTimeBy(10_000)
        success.captured.onSuccess(mockk(relaxed = true))
        advanceTimeBy(200_000)
        job.join()
        assertEquals(listOf<UploadStatus>(UploadStatus.Success("https://x/y")), emitted)
    }

    // ---------- deleteFileByUrl: parseo de path ----------

    @Test
    fun storagePathParsedFromDownloadUrl() {
        assertEquals(
            "videos/u1/video_1.mp4",
            storagePathFromDownloadUrl("http://10.0.2.2:9199/v0/b/b/o/videos%2Fu1%2Fvideo_1.mp4?alt=media&token=t")
        )
        assertNull(storagePathFromDownloadUrl("https://x/o/thumbnails%2Fu1%2Fa.jpg?alt=media"))
        assertNull(storagePathFromDownloadUrl("https://x/o/videos%2F..%2Fa%2Fb?alt=media"))
        assertNull(storagePathFromDownloadUrl("https://example.com/nothing"))
    }
}
