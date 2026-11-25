package com.negociodigital.boomday.ui

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.ReportRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoPage
import com.negociodigital.boomday.data.repository.VideoRepository
import com.negociodigital.boomday.ui.explore.ExploreViewModel
import com.negociodigital.boomday.ui.feed.FeedState
import com.negociodigital.boomday.ui.feed.FeedViewModel
import com.negociodigital.boomday.ui.ranking.RankingState
import com.negociodigital.boomday.ui.ranking.RankingViewModel
import com.negociodigital.boomday.ui.util.PublishSignal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** B4: mensajes en español sin texto crudo del SDK, recuperación idempotente y sin estados infinitos. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryB4Test {

    private val dispatcher = StandardTestDispatcher()
    private val users = mockk<UserRepository>()
    private val videos = mockk<VideoRepository>()
    private val reports = mockk<ReportRepository>(relaxed = true)
    private val auth = mockk<FirebaseAuth>()
    private val good = Video(videoId = "vg", userId = "good")
    private val okPage = Result.success(VideoPage(listOf(good), "c", true))
    private val sdkOffline = IOException(
        "Failed to get document because the client is offline."
    ) // FirebaseFirestoreException/FirebaseNetworkException no se instancian en JVM puro; mismo criterio isOfflineError

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.currentUser } returns mockk<FirebaseUser> { every { uid } returns "me" }
        coEvery { users.getBlockedUsers("me") } returns Result.success(emptySet())
        every { videos.getTodayVideos(any()) } returns flowOf(listOf(good))
        every { videos.getTopVideos(any()) } returns flowOf(listOf(good))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun feed() = FeedViewModel(videos, users, reports, auth)

    // ---- D1 ----
    @Test
    fun feed_sdkOfflineException_showsSpanishOfflineRes() = runTest(dispatcher) {
        coEvery { videos.getTodayVideosPage(any(), any()) } returns Result.failure(sdkOffline)
        val vm = feed(); advanceUntilIdle()
        assertEquals(FeedState.Error(R.string.feed_error_offline), vm.feedState.value)
    }

    @Test
    fun feed_otherException_showsGenericRes_notRawMessage() = runTest(dispatcher) {
        coEvery { videos.getTodayVideosPage(any(), any()) } returns Result.failure(RuntimeException("raw sdk text"))
        val vm = feed(); advanceUntilIdle()
        assertEquals(FeedState.Error(R.string.feed_error_generic), vm.feedState.value)
    }

    @Test
    fun feed_blockedUsersFailure_mapsOffline() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.failure(sdkOffline)
        val vm = feed(); advanceUntilIdle()
        assertEquals(FeedState.Error(R.string.feed_error_offline), vm.feedState.value)
    }

    @Test
    fun explore_sdkOffline_showsOfflineRes_andGenericOtherwise() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.failure(sdkOffline)
        val vm = ExploreViewModel(videos, users, auth)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(R.string.explore_error_offline, vm.uiState.value.error)

        coEvery { users.getBlockedUsers("me") } returns Result.failure(RuntimeException("raw"))
        vm.refresh(); advanceUntilIdle()
        assertEquals(R.string.explore_error_generic, vm.uiState.value.error)
    }

    // ---- D2 ----
    @Test
    fun feed_retryTaps_areIdempotent() = runTest(dispatcher) {
        coEvery { videos.getTodayVideosPage(any(), any()) } returns Result.failure(sdkOffline)
        val vm = feed(); advanceUntilIdle()
        coEvery { videos.getTodayVideosPage(any(), any()) } coAnswers { delay(100); okPage }

        // Cada tap ocurre con la carga anterior ya en vuelo (runCurrent la arranca).
        vm.refreshFeed(); runCurrent(); vm.refreshFeed(); runCurrent(); vm.refreshFeed()
        advanceUntilIdle()

        coVerify(exactly = 2) { videos.getTodayVideosPage(any(), any()) } // 1 inicial + 1 retry
        assertTrue(vm.feedState.value is FeedState.Success)
    }

    @Test
    fun feed_onResume_retriesOnlyOnce_andOnlyInError() = runTest(dispatcher) {
        coEvery { videos.getTodayVideosPage(any(), any()) } returns Result.failure(sdkOffline)
        val vm = feed(); advanceUntilIdle()

        vm.onScreenResumed(); advanceUntilIdle() // 1 auto-reintento (falla de nuevo)
        vm.onScreenResumed(); advanceUntilIdle() // sin bucle: no reintenta otra vez
        coVerify(exactly = 2) { videos.getTodayVideosPage(any(), any()) }
        assertTrue(vm.feedState.value is FeedState.Error)

        // Un reintento manual que triunfa; en Success onResume no hace nada.
        coEvery { videos.getTodayVideosPage(any(), any()) } returns okPage
        vm.refreshFeed(); advanceUntilIdle()
        vm.onScreenResumed(); advanceUntilIdle()
        coVerify(exactly = 3) { videos.getTodayVideosPage(any(), any()) }
    }

    @Test
    fun explore_retryTaps_areIdempotent_andResumeRetriesOnce() = runTest(dispatcher) {
        coEvery { users.getBlockedUsers("me") } returns Result.failure(sdkOffline)
        val vm = ExploreViewModel(videos, users, auth)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.onScreenResumed(); advanceUntilIdle()
        vm.onScreenResumed(); advanceUntilIdle()
        coVerify(exactly = 2) { users.getBlockedUsers("me") } // inicial + 1 auto

        coEvery { users.getBlockedUsers("me") } coAnswers { delay(100); Result.success(emptySet()) }
        vm.refresh(); runCurrent(); vm.refresh(); runCurrent(); vm.refresh()
        advanceUntilIdle()
        coVerify(exactly = 3) { users.getBlockedUsers("me") } // 3 taps = 1 carga
        assertEquals(null, vm.uiState.value.error)
    }

    // ---- D3 ----
    private fun ranking() = RankingViewModel(videos, users, auth)

    @Test
    fun ranking_onResume_inError_reloads() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returnsMany listOf(
            flow { throw sdkOffline }, flowOf(listOf(good))
        )
        val vm = ranking(); advanceUntilIdle()
        assertEquals(RankingState.Error(R.string.ranking_error_offline), vm.rankingState.value)
        vm.onScreenResumed(); advanceUntilIdle()
        assertTrue(vm.rankingState.value is RankingState.Success)
    }

    @Test
    fun ranking_onResume_reloadsOnlyAfterPublish() = runTest(dispatcher) {
        val vm = ranking(); advanceUntilIdle()
        vm.onScreenResumed(); advanceUntilIdle()
        verify(exactly = 1) { videos.getTopVideos(any()) } // sin publicación: no recarga

        PublishSignal.bump()
        vm.onScreenResumed(); advanceUntilIdle()
        verify(exactly = 2) { videos.getTopVideos(any()) }
        vm.onScreenResumed(); advanceUntilIdle()
        verify(exactly = 2) { videos.getTopVideos(any()) } // ya actualizado
        assertTrue(vm.rankingState.value is RankingState.Success)
    }

    // ---- D4 ----
    @Test
    fun feed_pageThatNeverResponds_endsInErrorWithRetry() = runTest(dispatcher) {
        coEvery { videos.getTodayVideosPage(any(), any()) } coAnswers {
            delay(Long.MAX_VALUE)
            okPage
        }
        val vm = feed(); advanceUntilIdle()
        assertEquals(FeedState.Error(R.string.feed_error_offline), vm.feedState.value)
    }

    @Test
    fun ranking_listenerThatNeverEmits_endsInError() = runTest(dispatcher) {
        val never: Flow<List<Video>> = flow { delay(Long.MAX_VALUE) }
        every { videos.getTopVideos(any()) } returns never
        val vm = ranking(); advanceUntilIdle()
        assertEquals(RankingState.Error(R.string.ranking_error_offline), vm.rankingState.value)
    }
}
