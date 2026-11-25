package com.negociodigital.boomday.feed

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.ReportRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoPage
import com.negociodigital.boomday.data.repository.VideoRepository
import com.negociodigital.boomday.ui.feed.FEED_PAGE_SIZE
import com.negociodigital.boomday.ui.feed.FeedState
import com.negociodigital.boomday.ui.feed.FeedViewModel
import com.negociodigital.boomday.ui.feed.NextPageState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedPaginationTest {

    private val dispatcher = StandardTestDispatcher()
    private val users = mockk<UserRepository>()
    private val repo = mockk<VideoRepository>()
    private val reports = mockk<ReportRepository>(relaxed = true)
    private val auth = mockk<FirebaseAuth>()

    private fun v(id: String, user: String = "u") = Video(videoId = id, userId = user)
    private fun ids(s: FeedState) = (s as FeedState.Success).videos.map { it.videoId }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.currentUser } returns mockk<FirebaseUser> { every { uid } returns "me" }
        coEvery { users.getBlockedUsers("me") } returns Result.success(setOf("bad"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = FeedViewModel(repo, users, reports, auth)

    /** Simula el servidor: cursor = índice de inicio en [all]; respeta startAfter. */
    private fun serverWith(all: List<Video>) {
        coEvery { repo.getTodayVideosPage(any(), any()) } coAnswers {
            val start = (firstArg<Any?>() as Int?) ?: 0
            val size = secondArg<Int>()
            val slice = all.drop(start).take(size)
            Result.success(VideoPage(slice, start + slice.size, slice.size < size))
        }
    }

    @Test
    fun firstPage_loadsOnlyPageSize_andIsIdle() = runTest(dispatcher) {
        serverWith((1..40).map { v("v$it") })
        val vm = vm(); advanceUntilIdle()
        val s = vm.feedState.value as FeedState.Success
        assertEquals(FEED_PAGE_SIZE, s.videos.size)
        assertEquals(NextPageState.Idle, s.nextPage)
    }

    @Test
    fun nextPage_appendsWithoutDuplicates_evenIfServerOverlaps() = runTest(dispatcher) {
        // La 2a página repite el último video de la 1a (solape): no debe duplicarse.
        val p1 = (1..FEED_PAGE_SIZE).map { v("v$it") }
        val p2 = (FEED_PAGE_SIZE..FEED_PAGE_SIZE + 5).map { v("v$it") }
        coEvery { repo.getTodayVideosPage(null, any()) } returns Result.success(VideoPage(p1, "c1", false))
        coEvery { repo.getTodayVideosPage("c1", any()) } returns Result.success(VideoPage(p2, "c2", true))
        val vm = vm(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        val all = ids(vm.feedState.value)
        assertEquals(all.distinct(), all)
        assertEquals(FEED_PAGE_SIZE + 5, all.size)
    }

    @Test
    fun cursorIsPassedToNextRequest() = runTest(dispatcher) {
        serverWith((1..20).map { v("v$it") })
        val vm = vm(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        coVerify(exactly = 1) { repo.getTodayVideosPage(null, FEED_PAGE_SIZE) }
        coVerify(exactly = 1) { repo.getTodayVideosPage(FEED_PAGE_SIZE, FEED_PAGE_SIZE) }
        assertEquals(20, ids(vm.feedState.value).size)
    }

    @Test
    fun lastPage_endsAndDoesNotRequestMore() = runTest(dispatcher) {
        serverWith((1..20).map { v("v$it") })
        val vm = vm(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(NextPageState.End, (vm.feedState.value as FeedState.Success).nextPage)
        vm.loadNextPage(); advanceUntilIdle()
        coVerify(exactly = 2) { repo.getTodayVideosPage(any(), any()) }
    }

    @Test
    fun nextPageError_keepsLoaded_andRetryRecovers() = runTest(dispatcher) {
        val p1 = (1..FEED_PAGE_SIZE).map { v("v$it") }
        val p2 = listOf(v("x1"))
        coEvery { repo.getTodayVideosPage(null, any()) } returns Result.success(VideoPage(p1, "c1", false))
        coEvery { repo.getTodayVideosPage("c1", any()) } returnsMany listOf(
            Result.failure(RuntimeException("net")),
            Result.success(VideoPage(p2, "c2", true))
        )
        val vm = vm(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        var s = vm.feedState.value as FeedState.Success
        assertEquals(NextPageState.Error, s.nextPage)
        assertEquals(FEED_PAGE_SIZE, s.videos.size)
        vm.loadNextPage(); advanceUntilIdle() // reintento
        s = vm.feedState.value as FeedState.Success
        assertEquals(NextPageState.End, s.nextPage)
        assertEquals(FEED_PAGE_SIZE + 1, s.videos.size)
    }

    @Test
    fun pageEmptyAfterBlockedFilter_loadsFollowingPage() = runTest(dispatcher) {
        val p1 = (1..FEED_PAGE_SIZE).map { v("v$it") }
        val allBad = (1..FEED_PAGE_SIZE).map { v("b$it", "bad") }
        val p3 = listOf(v("z1"))
        coEvery { repo.getTodayVideosPage(null, any()) } returns Result.success(VideoPage(p1, "c1", false))
        coEvery { repo.getTodayVideosPage("c1", any()) } returns Result.success(VideoPage(allBad, "c2", false))
        coEvery { repo.getTodayVideosPage("c2", any()) } returns Result.success(VideoPage(p3, "c3", true))
        val vm = vm(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        val s = vm.feedState.value as FeedState.Success
        assertEquals(FEED_PAGE_SIZE + 1, s.videos.size)
        assertTrue(s.videos.none { it.userId == "bad" })
        assertEquals(NextPageState.End, s.nextPage)
    }

    @Test
    fun firstPageAllBlockedAndEnd_isEmpty() = runTest(dispatcher) {
        coEvery { repo.getTodayVideosPage(null, any()) } returns Result.success(VideoPage(listOf(v("b", "bad")), "c", true))
        val vm = vm(); advanceUntilIdle()
        assertEquals(FeedState.Empty, vm.feedState.value)
    }

    @Test
    fun refresh_restartsFromFirstPageWithoutCursor() = runTest(dispatcher) {
        serverWith((1..20).map { v("v$it") })
        val vm = vm(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        vm.refreshFeed(); advanceUntilIdle()
        val s = vm.feedState.value as FeedState.Success
        assertEquals(FEED_PAGE_SIZE, s.videos.size)
        coVerify(exactly = 2) { repo.getTodayVideosPage(null, FEED_PAGE_SIZE) }
    }

    @Test
    fun blockingEveryLoadedVideo_withMorePages_loadsNextInsteadOfEmpty() = runTest(dispatcher) {
        val p1 = listOf(v("a", "x"), v("b", "x"))
        coEvery { repo.getTodayVideosPage(null, any()) } returns Result.success(VideoPage(p1, "c1", false))
        coEvery { repo.getTodayVideosPage("c1", any()) } returns Result.success(VideoPage(listOf(v("c", "y")), "c2", true))
        coEvery { users.blockUser("me", "x") } returns Result.success(Unit)
        val vm = vm(); advanceUntilIdle()
        vm.blockUser("x"); advanceUntilIdle()
        assertEquals(listOf("c"), ids(vm.feedState.value))
    }
}
