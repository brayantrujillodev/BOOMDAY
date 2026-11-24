package com.negociodigital.boomday.ui.ranking

import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
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
import java.io.IOException

/**
 * Recuperación del ranking: retry() solo desde Error, sin cargas duplicadas y liberando el
 * listener anterior (el awaitClose de los flows de prueba cuenta suscripciones/cierres).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RankingViewModelRetryTest {

    private val dispatcher = StandardTestDispatcher()
    private val users = mockk<UserRepository>()
    private val videos = mockk<VideoRepository>()
    private val auth = mockk<FirebaseAuth>()
    private val good = Video(userId = "good")

    private var subscriptions = 0
    private var closes = 0

    /** Simula el callbackFlow de getTopVideos: emite [list] y queda vivo hasta cancelarse. */
    private fun liveFlow(list: List<Video>): Flow<List<Video>> = callbackFlow {
        subscriptions++
        trySend(list)
        awaitClose { closes++ }
    }

    /** Simula un fallo del listener (addSnapshotListener -> error). */
    private fun failingFlow(e: Throwable): Flow<List<Video>> = callbackFlow {
        subscriptions++
        close(e)
        awaitClose { closes++ }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val user = mockk<FirebaseUser> { every { uid } returns "me" }
        every { auth.currentUser } returns user
        coEvery { users.getBlockedUsers("me") } returns Result.success(emptySet())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun forceState(vm: RankingViewModel, state: RankingState) {
        val f = RankingViewModel::class.java.getDeclaredField("_rankingState")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (f.get(vm) as MutableStateFlow<RankingState>).value = state
    }

    @Test
    fun error_retry_success() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returnsMany listOf(
            failingFlow(IOException("net")),
            liveFlow(listOf(good))
        )
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertEquals(RankingState.Error(R.string.ranking_error_offline), vm.rankingState.value)

        vm.retry()
        assertEquals(RankingState.Loading, vm.rankingState.value)
        advanceUntilIdle()
        val s = vm.rankingState.value as RankingState.Success
        assertEquals(listOf(good), s.ranking.map { it.video })
    }

    @Test
    fun genericError_usesGenericMessage() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returns failingFlow(RuntimeException("PERMISSION raw sdk text"))
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertEquals(RankingState.Error(R.string.ranking_error_generic), vm.rankingState.value)
    }

    @Test
    fun blockedTimeout_isOfflineError_failClosed_thenRetryRecovers() = runTest(dispatcher) {
        var calls = 0
        coEvery { users.getBlockedUsers("me") } coAnswers {
            if (calls++ == 0) {
                delay(Long.MAX_VALUE)
            }
            Result.success(emptySet())
        }
        every { videos.getTopVideos(any()) } returns liveFlow(listOf(good))
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertEquals(RankingState.Error(R.string.ranking_error_offline), vm.rankingState.value)
        verify(exactly = 0) { videos.getTopVideos(any()) }

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.rankingState.value is RankingState.Success)
    }

    @Test
    fun doubleRetry_doesNotLaunchTwoLoads() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returnsMany listOf(
            failingFlow(IOException("net")),
            liveFlow(listOf(good))
        )
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()

        vm.retry()
        vm.retry()
        vm.retry()
        advanceUntilIdle()

        verify(exactly = 2) { videos.getTopVideos(any()) } // 1 inicial + 1 retry
        assertEquals(2, subscriptions)
        assertTrue(vm.rankingState.value is RankingState.Success)
    }

    @Test
    fun retry_inSuccess_doesNothing() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returns liveFlow(listOf(good))
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        val before = vm.rankingState.value
        assertTrue(before is RankingState.Success)

        vm.retry()
        advanceUntilIdle()

        assertEquals(before, vm.rankingState.value)
        verify(exactly = 1) { videos.getTopVideos(any()) }
        assertEquals(0, closes)
    }

    @Test
    fun retry_inLoading_doesNothing() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returns liveFlow(listOf(good))
        val vm = RankingViewModel(videos, users, auth)
        assertEquals(RankingState.Loading, vm.rankingState.value)

        vm.retry() // aún Loading: no debe relanzar
        advanceUntilIdle()

        verify(exactly = 1) { videos.getTopVideos(any()) }
        assertEquals(1, subscriptions)
    }

    @Test
    fun retry_inEmpty_doesNothing() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returns liveFlow(emptyList())
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertEquals(RankingState.Empty, vm.rankingState.value)

        vm.retry()
        advanceUntilIdle()

        verify(exactly = 1) { videos.getTopVideos(any()) }
    }

    @Test
    fun retry_releasesPreviousListener() = runTest(dispatcher) {
        // El job anterior sigue vivo (listener activo) cuando el estado es Error; se fuerza ese
        // escenario por reflexión. Si se quita `loadJob?.cancel()` de loadRanking(), el listener
        // 1 nunca se cierra (closes == 0) y esta prueba FALLA (verificado por mutación mental:
        // sin cancel, el awaitClose del primer flow no se ejecuta hasta cancelar el scope).
        every { videos.getTopVideos(any()) } returnsMany listOf(
            liveFlow(listOf(good)),
            liveFlow(listOf(good))
        )
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertEquals(1, subscriptions)
        assertEquals(0, closes)

        forceState(vm, RankingState.Error(R.string.ranking_error_generic))
        vm.retry()
        advanceUntilIdle()

        assertEquals(2, subscriptions)
        assertEquals("el listener anterior debe liberarse al reintentar", 1, closes)
        assertTrue(vm.rankingState.value is RankingState.Success)
    }

    @Test
    fun cancellingViewModelScope_releasesListener() = runTest(dispatcher) {
        every { videos.getTopVideos(any()) } returns liveFlow(listOf(good))
        val vm = RankingViewModel(videos, users, auth)
        advanceUntilIdle()
        assertEquals(1, subscriptions)
        assertEquals(0, closes)

        vm.viewModelScope.cancel()
        advanceUntilIdle()

        assertEquals(1, closes)
    }
}
