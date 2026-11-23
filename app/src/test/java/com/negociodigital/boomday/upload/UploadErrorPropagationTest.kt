package com.negociodigital.boomday.upload

import android.net.Uri
import com.google.android.gms.tasks.OnFailureListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.negociodigital.boomday.data.model.UploadStatus
import com.negociodigital.boomday.data.repository.StorageRepository
import com.negociodigital.boomday.data.repository.UploadRepository
import com.negociodigital.boomday.ui.upload.UploadState
import com.negociodigital.boomday.ui.upload.UploadViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Regresion P0-2: un fallo de subida NO debe propagarse como excepcion al colector
 * (antes: close(exception) en callbackFlow -> crash por excepcion sin handler).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadErrorPropagationTest {

    @Before fun setUp() { Dispatchers_setMain() }
    @After fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain() }

    private fun Dispatchers_setMain() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @Test
    fun storageFailureIsEmittedAsErrorAndDoesNotThrowToCollector() = runTest {
        val user = mockk<FirebaseUser>().also { every { it.uid } returns "u1" }
        val auth = mockk<FirebaseAuth>().also { every { it.currentUser } returns user }
        val storage = mockk<FirebaseStorage>()
        val root = mockk<StorageReference>()
        val child = mockk<StorageReference>()
        val task = mockk<UploadTask>(relaxed = true)
        val failSlot = slot<OnFailureListener>()
        every { storage.reference } returns root
        every { root.child(any()) } returns child
        every { child.putFile(any(), any<StorageMetadata>()) } returns task
        every { task.addOnFailureListener(capture(failSlot)) } returns task

        val repo = StorageRepository(storage, auth)
        val deferred = async(UnconfinedTestDispatcher(testScheduler)) {
            runCatching { repo.uploadVideoWithProgress(mockk<Uri>()).toList() }
        }
        failSlot.captured.onFailure(IOException("sin red"))

        val result = deferred.await()
        assertTrue("El colector no debe recibir excepcion: ${result.exceptionOrNull()}", result.isSuccess)
        val last = result.getOrThrow().last()
        assertTrue(last is UploadStatus.Error)
    }

    @Test
    fun viewModelLeavesUploadingWhenRepositoryFlowThrows() = runTest {
        val uri = mockk<Uri>().also { every { it.scheme } returns "content" }
        val repo = mockk<UploadRepository>()
        every { repo.uploadVideoComplete(any(), any(), any(), any(), any()) } returns flow { throw IOException("boom") }

        val vm = UploadViewModel(repo)
        vm.setRecordedVideo(uri, 5000)
        vm.confirmUpload("t", "d", emptyList())

        val state = vm.uploadState.value
        assertFalse("No debe quedar en Uploading", state is UploadState.Uploading)
        assertTrue(state is UploadState.Error)
    }
}
