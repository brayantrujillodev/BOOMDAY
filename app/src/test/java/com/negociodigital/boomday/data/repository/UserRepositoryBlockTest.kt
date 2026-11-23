package com.negociodigital.boomday.data.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UserRepositoryBlockTest {

    private val firestore = mockk<FirebaseFirestore>()
    private val collection = mockk<CollectionReference>()
    private val doc = mockk<DocumentReference>()
    private lateinit var repo: UserRepository

    @Before
    fun setUp() {
        every { firestore.collection("users") } returns collection
        every { collection.document(any()) } returns doc
        repo = UserRepository(firestore)
    }

    private fun <T> okTask(value: T): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns null
        every { result } returns value
    }

    private fun <T> failedTask(e: Exception): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns e
    }

    private fun <T> canceledTask(): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns true
        every { exception } returns null
    }

    private fun snapshot(field: Any?): DocumentSnapshot {
        val s = mockk<DocumentSnapshot>()
        every { s.get("blockedUsers") } returns field
        return s
    }

    @Test
    fun getBlockedUsers_docWithoutField_isSuccessEmpty() = runTest {
        every { doc.get() } returns okTask(snapshot(null))
        assertEquals(emptySet<String>(), repo.getBlockedUsers("u").getOrThrow())
    }

    @Test
    fun getBlockedUsers_withList_andInvalidElements() = runTest {
        every { doc.get() } returns okTask(snapshot(listOf("a", 3, "b", null)))
        assertEquals(setOf("a", "b"), repo.getBlockedUsers("u").getOrThrow())
    }

    @Test
    fun getBlockedUsers_realFailure_isFailureNotEmpty() = runTest {
        val boom = RuntimeException("offline")
        every { doc.get() } returns failedTask(boom)
        val r = repo.getBlockedUsers("u")
        assertTrue(r.isFailure)
        assertSame(boom, r.exceptionOrNull())
    }

    @Test
    fun getBlockedUsers_cancellation_isRethrown() = runTest {
        every { doc.get() } returns canceledTask()
        try {
            repo.getBlockedUsers("u")
            fail("CancellationException debia relanzarse")
        } catch (e: CancellationException) {
            // esperado
        }
    }

    @Test
    fun blockUser_usesSetMerge_andSucceeds() = runTest {
        val data = slot<Map<String, Any>>()
        val opts = slot<SetOptions>()
        every { doc.set(capture(data), capture(opts)) } returns okTask<Void?>(null) as Task<Void>
        assertTrue(repo.blockUser("me", "bad").isSuccess)
        assertEquals(setOf("blockedUsers"), data.captured.keys)
        assertEquals(SetOptions.merge(), opts.captured)
        verify(exactly = 0) { doc.update(any<String>(), any()) }
    }

    @Test
    fun unblockUser_usesSetMerge_andSucceeds() = runTest {
        val data = slot<Map<String, Any>>()
        every { doc.set(capture(data), any<SetOptions>()) } returns okTask<Void?>(null) as Task<Void>
        assertTrue(repo.unblockUser("me", "bad").isSuccess)
        assertEquals(setOf("blockedUsers"), data.captured.keys)
    }

    @Test
    fun blockAndUnblock_failure_returnsFailure_cancellationRethrown() = runTest {
        val boom = RuntimeException("denied")
        every { doc.set(any<Map<String, Any>>(), any<SetOptions>()) } returns failedTask<Void>(boom)
        assertSame(boom, repo.blockUser("me", "x").exceptionOrNull())
        assertSame(boom, repo.unblockUser("me", "x").exceptionOrNull())

        every { doc.set(any<Map<String, Any>>(), any<SetOptions>()) } returns canceledTask<Void>()
        try {
            repo.blockUser("me", "x")
            fail("debia relanzar")
        } catch (e: CancellationException) {
            // esperado
        }
        try {
            repo.unblockUser("me", "x")
            fail("debia relanzar")
        } catch (e: CancellationException) {
            // esperado
        }
    }
}
