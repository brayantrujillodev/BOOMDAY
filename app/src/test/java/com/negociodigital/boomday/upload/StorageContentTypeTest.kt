package com.negociodigital.boomday.upload

import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.negociodigital.boomday.data.repository.StorageRepository
import com.negociodigital.boomday.data.repository.thumbnailMetadata
import com.negociodigital.boomday.data.repository.videoContentTypeFor
import com.negociodigital.boomday.data.repository.videoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException

/** Regresion: storage.rules exige contentType de video e image; sin metadata la subida da 403. */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageContentTypeTest {

    @Test
    fun contentTypeForFallsBackToMp4UnlessVideo() {
        assertEquals("video/mp4", videoContentTypeFor(null))
        assertEquals("video/mp4", videoContentTypeFor("application/octet-stream"))
        assertEquals("video/mp4", videoContentTypeFor("image/jpeg"))
        assertEquals("video/mp4", videoContentTypeFor(""))
        assertEquals("video/webm", videoContentTypeFor("video/webm"))
        assertEquals("video/quicktime", videoContentTypeFor("video/quicktime"))
        assertEquals("video/mp4", videoMetadata(null).contentType)
        assertEquals("image/jpeg", thumbnailMetadata().contentType)
    }

    private fun fixtures(): Triple<StorageRepository, StorageReference, UploadTask> {
        val user = mockk<FirebaseUser>().also { every { it.uid } returns "u1" }
        val auth = mockk<FirebaseAuth>().also { every { it.currentUser } returns user }
        val storage = mockk<FirebaseStorage>()
        val root = mockk<StorageReference>()
        val child = mockk<StorageReference>()
        val task = mockk<UploadTask>(relaxed = true)
        every { storage.reference } returns root
        every { root.child(any()) } returns child
        return Triple(StorageRepository(storage, auth), child, task)
    }

    @Test
    fun uploadVideoWithProgressSendsVideoContentType() = runTest {
        val (repo, child, task) = fixtures()
        val meta = slot<StorageMetadata>()
        val fail = slot<OnFailureListener>()
        every { child.putFile(any(), capture(meta)) } returns task
        every { task.addOnFailureListener(capture(fail)) } returns task

        val d = async(UnconfinedTestDispatcher(testScheduler)) {
            repo.uploadVideoWithProgress(mockk<Uri>()).toList()
        }
        fail.captured.onFailure(IOException("fin"))
        d.await()
        assertEquals("video/mp4", meta.captured.contentType)
    }

    @Test
    fun uploadVideoWithProgressHonorsResolvedVideoType() = runTest {
        val (repo, child, task) = fixtures()
        val meta = slot<StorageMetadata>()
        val fail = slot<OnFailureListener>()
        every { child.putFile(any(), capture(meta)) } returns task
        every { task.addOnFailureListener(capture(fail)) } returns task

        val d = async(UnconfinedTestDispatcher(testScheduler)) {
            repo.uploadVideoWithProgress(mockk<Uri>(), "video/webm").toList()
        }
        fail.captured.onFailure(IOException("fin"))
        d.await()
        assertEquals("video/webm", meta.captured.contentType)
    }

    @Test
    fun uploadVideoSendsVideoContentType() = runTest {
        val (repo, child, _) = fixtures()
        val meta = slot<StorageMetadata>()
        every { child.putFile(any(), capture(meta)) } returns mockk<UploadTask>(relaxed = true).also {
            // Falla el await de forma controlada: solo importa la metadata enviada.
            every { it.isComplete } returns true
            every { it.isCanceled } returns false
            every { it.isSuccessful } returns false
            every { it.exception } returns IOException("x")
        }
        val result = repo.uploadVideo(mockk<Uri>())
        assertNotNull(result)
        assertEquals("video/mp4", meta.captured.contentType)
    }
}
