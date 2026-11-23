package com.negociodigital.boomday.upload

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.StorageRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class VideoRepositorySaveTest {

    private val doc = mockk<DocumentReference>()
    private lateinit var repo: VideoRepository
    private val video = Video(userId = "u1", videoUrl = "https://blob2")

    private fun <T> done(value: T?, error: Exception? = null): Task<T> = mockk<Task<T>>().also {
        every { it.isComplete } returns true
        every { it.isCanceled } returns false
        every { it.exception } returns error
        if (error == null) every { it.result } returns value
    }

    @Before
    fun setUp() {
        val col = mockk<CollectionReference>()
        val fs = mockk<FirebaseFirestore>()
        every { fs.collection("videos") } returns col
        every { col.document("vid-1") } returns doc
        every { doc.id } returns "vid-1"
        every { doc.set(any()) } returns done<Void>(null)
        repo = VideoRepository(fs, mockk<FirebaseAuth>(), mockk<StorageRepository>())
    }

    private fun existing(owner: String?) {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns true
        every { snap.getString("userId") } returns owner
        every { doc.get() } returns done(snap)
    }

    @Test
    fun missingDocPerformsNormalSet() = runBlocking {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns false
        every { doc.get() } returns done(snap)
        assertEquals("vid-1", repo.saveVideo(video, "vid-1").getOrThrow())
        verify(exactly = 1) { doc.set(any()) }
    }

    @Test
    fun getFailureFallsBackToSet() = runBlocking {
        every { doc.get() } returns done<DocumentSnapshot>(null, IOException("offline"))
        assertEquals("vid-1", repo.saveVideo(video, "vid-1").getOrThrow())
        verify(exactly = 1) { doc.set(any()) }
    }

    @Test
    fun existingDocOfSameUserIsAlreadyPublishedWithoutSet() = runBlocking {
        existing("u1")
        val e = repo.saveVideo(video, "vid-1").exceptionOrNull()
        assertTrue(e is UploadException.AlreadyPublished)
        verify(exactly = 0) { doc.set(any()) }
    }

    @Test
    fun existingDocOfAnotherUserIsConflictWithoutSet() = runBlocking {
        existing("otro")
        val e = repo.saveVideo(video, "vid-1").exceptionOrNull()
        assertTrue(e is UploadException.IdConflict)
        verify(exactly = 0) { doc.set(any()) }
    }
}
