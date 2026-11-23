package com.negociodigital.boomday.upload

import android.net.Uri
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.repository.UploadRepository
import com.negociodigital.boomday.ui.upload.UploadState
import com.negociodigital.boomday.ui.upload.UploadViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelTest {

    private val repo = mockk<UploadRepository>()
    private lateinit var vm: UploadViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = UploadViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun contentUri(name: String = "a") = mockk<Uri>().also {
        every { it.scheme } returns "content"
        every { it.path } returns "/$name"
    }

    private fun fileUri(f: File) = mockk<Uri>().also {
        every { it.scheme } returns "file"
        every { it.path } returns f.absolutePath
    }

    private fun states(vararg s: UploadStatus) {
        every { repo.uploadVideoComplete(any(), any(), any(), any(), any()) } returns flow { s.forEach { emit(it) } }
    }

    private fun start(uri: Uri = contentUri()) {
        vm.setRecordedVideo(uri, 5000)
        vm.confirmUpload("t", "d", emptyList())
    }

    @Test
    fun errorStatusEndsInErrorWithSpanishResourceNotRawMessage() {
        states(UploadStatus.Progress(10), UploadStatus.Error(IOException("raw firebase text")))
        start()
        val s = vm.uploadState.value as UploadState.Error
        assertEquals(R.string.upload_error_network, s.messageRes)
    }

    @Test
    fun userNotLoggedInMapsToSpecificMessage() {
        states(UploadStatus.Error(UploadException.NotLoggedIn()))
        start()
        assertEquals(R.string.upload_error_not_logged_in, (vm.uploadState.value as UploadState.Error).messageRes)
    }

    @Test
    fun timeoutMapsToTimeoutMessage() {
        states(UploadStatus.Error(UploadException.Timeout()))
        start()
        assertEquals(R.string.upload_error_timeout, (vm.uploadState.value as UploadState.Error).messageRes)
    }

    @Test
    fun saveVideoFailureEndsInError() {
        states(UploadStatus.Progress(100), UploadStatus.Error(IllegalStateException("firestore")))
        start()
        assertTrue(vm.uploadState.value is UploadState.Error)
    }

    @Test
    fun flowThatThrowsNeverLeavesUploading() {
        every { repo.uploadVideoComplete(any(), any(), any(), any(), any()) } returns flow { throw IOException("boom") }
        start()
        assertTrue(vm.uploadState.value is UploadState.Error)
    }

    @Test
    fun flowThatEndsWithoutTerminalNeverLeavesUploading() {
        states(UploadStatus.Progress(30))
        start()
        assertFalse(vm.uploadState.value is UploadState.Uploading)
        assertTrue(vm.uploadState.value is UploadState.Error)
    }

    @Test
    fun cancelUploadGoesToCancelledNotError() {
        every { repo.uploadVideoComplete(any(), any(), any(), any(), any()) } returns MutableSharedFlow<UploadStatus>()
        start()
        assertTrue(vm.uploadState.value is UploadState.Uploading)
        vm.cancelUpload()
        assertEquals(UploadState.Cancelled, vm.uploadState.value)
    }

    @Test
    fun cancelAfterSuccessDoesNotOverwriteState() {
        states(UploadStatus.Success("vid"))
        start()
        vm.cancelUpload()
        assertEquals(UploadState.Success("vid"), vm.uploadState.value)
    }

    @Test
    fun retryAfterErrorReusesSameVideoIdSoNoDuplicateDocument() {
        val ids = mutableListOf<String?>()
        val idSlot = slot<String?>()
        every { repo.uploadVideoComplete(any(), any(), any(), any(), captureNullable(idSlot)) } answers {
            ids += idSlot.captured
            flow { emit(UploadStatus.Error(IOException("x"))) }
        }
        val uri = contentUri()
        start(uri)
        assertTrue(vm.uploadState.value is UploadState.Error)
        vm.setRecordedVideo(uri, 5000) // "Reintentar" en la UI
        vm.confirmUpload("t", "d", emptyList())
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        // Un video distinto sí obtiene un id nuevo
        vm.setRecordedVideo(contentUri("b"), 5000)
        vm.confirmUpload("t", "d", emptyList())
        assertNotEquals(ids[0], ids[2])
    }

    @Test
    fun doubleConfirmWhileUploadingStartsOnlyOneUpload() {
        var calls = 0
        every { repo.uploadVideoComplete(any(), any(), any(), any(), any()) } answers {
            calls++
            MutableSharedFlow<UploadStatus>()
        }
        start()
        vm.confirmUpload("t", "d", emptyList())
        assertEquals(1, calls)
    }

    @Test
    fun successDeletesOwnedTempFile() {
        val f = File.createTempFile("boomday_rec_", ".mp4")
        states(UploadStatus.Success("vid"))
        start(fileUri(f))
        assertFalse(f.exists())
    }

    @Test
    fun errorKeepsTempFileForRetryAndDiscardDeletesIt() {
        val f = File.createTempFile("boomday_rec_", ".mp4")
        states(UploadStatus.Error(IOException("x")))
        start(fileUri(f))
        assertTrue("Se conserva para Reintentar", f.exists())
        vm.resetState()
        assertFalse("Descartar lo borra", f.exists())
    }

    @Test
    fun neverDeletesFilesThatAreNotOurRecordings() {
        val f = File.createTempFile("otro_", ".mp4")
        try {
            vm.setRecordedVideo(fileUri(f), 5000)
            vm.discardRecording()
            assertTrue(f.exists())
        } finally {
            f.delete()
        }
    }
}
