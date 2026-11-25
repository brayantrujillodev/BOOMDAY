package com.negociodigital.boomday.ui

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.ReportRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import com.negociodigital.boomday.ui.explore.ExploreViewModel
import com.negociodigital.boomday.ui.feed.FeedState
import com.negociodigital.boomday.ui.feed.FeedViewModel
import com.negociodigital.boomday.ui.ranking.RankingState
import com.negociodigital.boomday.ui.ranking.RankingViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import com.negociodigital.boomday.data.repository.VideoPage
import com.negociodigital.boomday.ui.feed.NextPageState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedUsersViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val users = mockk<UserRepository>()
    private val videos = mockk<VideoRepository>()
    private val reports = mockk<ReportRepository>(relaxed = true)
    private val auth = mockk<FirebaseAuth>()

    private val bad = Video(videoId = "vb", userId = "bad")
    private val good = Video(videoId = "vg", userId = "good")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val user = mockk<FirebaseUser> { every { uid } returns "me" }
        every { auth.currentUser } returns user
        every { videos.getTodayVideos(any()) } returns flowOf(listOf(bad, good))
        coEvery { videos.getTodayVideosPage(any(), any()) } returns Result.success(VideoPage(listOf(bad, good), "c", true))
        every { videos.getTopVideos(any()) } returns flowOf(listOf(bad, good))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- Feed ----
    @Test
    fun feed_blockedFailure_isError_andDoesNotSubscribe() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.failure(RuntimeException("offline"))
        val vm = FeedViewModel(videos, users, reports, auth)
        advanceUntilIdle()
        assertTrue(vm.feedState.value.toString(), vm.feedState.value is FeedState.Error)
        coVerify(exactly = 0) { videos.getTodayVideosPage(any(), any()) }
    }

    @Test
    fun feed_success_filtersBlocked() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.success(setOf("bad"))
        val vm = FeedViewModel(videos, users, reports, auth)
        advanceUntilIdle()
        assertEquals(FeedState.Success(listOf(good), NextPageState.End), vm.feedState.value)
    }

    @Test
    fun feed_retryAfterFailure_recovers() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returnsMany listOf(
            Result.failure(RuntimeException("x")),
            Result.success(setOf("bad"))
        )
        val vm = FeedViewModel(videos, users, reports, auth)
        advanceUntilIdle()
        assertTrue(vm.feedState.value is FeedState.Error)
        vm.refreshFeed()
        advanceUntilIdle()
        assertEquals(FeedState.Success(listOf(good), NextPageState.End), vm.feedState.value)
    }

    @Test
    fun feed_blockedLoadNeverResponds_endsInError() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } coAnswers {
            delay(Long.MAX_VALUE)
            Result.success(emptySet())
        }
        val vm = FeedViewModel(videos, users, reports, auth)
        advanceUntilIdle()
        assertTrue(vm.feedState.value.toString(), vm.feedState.value is FeedState.Error)
        coVerify(exactly = 0) { videos.getTodayVideosPage(any(), any()) }
    }

    // ---- Ranking ----
    @Test
    fun ranking_blockedFailure_isError_andDoesNotSubscribe() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.failure(RuntimeException("offline"))
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertTrue(vm.rankingState.value is RankingState.Error)
        verify(exactly = 0) { videos.getTopVideos(any()) }
    }

    @Test
    fun ranking_success_filtersBlocked() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.success(setOf("bad"))
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        val s = vm.rankingState.value as RankingState.Success
        assertEquals(listOf(good), s.ranking.map { it.video })
    }

    @Test
    fun ranking_blockedLoadNeverResponds_endsInError() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } coAnswers {
            delay(Long.MAX_VALUE)
            Result.success(emptySet())
        }
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertTrue(vm.rankingState.value is RankingState.Error)
        verify(exactly = 0) { videos.getTopVideos(any()) }
    }

    // ---- Explore ----
    @Test
    fun explore_blockedFailure_failClosed() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.failure(RuntimeException("offline"))
        val vm = ExploreViewModel(videos, users, auth)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        val st = vm.uiState.value
        assertNotNull(st.error)
        assertTrue(!st.isLoading)
        assertTrue(st.trending.isEmpty() && st.recent.isEmpty())
        verify(exactly = 0) { videos.getTopVideos(any()) }
    }

    @Test
    fun explore_success_filtersBlocked() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.success(setOf("bad"))
        val vm = ExploreViewModel(videos, users, auth)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(listOf(good), vm.uiState.value.trending)
        assertEquals(listOf(good), vm.uiState.value.recent)
    }

    @Test
    fun explore_blockedLoadNeverResponds_endsInError() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } coAnswers {
            delay(Long.MAX_VALUE)
            Result.success(emptySet())
        }
        val vm = ExploreViewModel(videos, users, auth)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)
        verify(exactly = 0) { videos.getTopVideos(any()) }
    }
}
