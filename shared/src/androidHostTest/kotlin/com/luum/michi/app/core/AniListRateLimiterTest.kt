package com.luum.michi.app.core

import com.luum.michi.app.core.network.repository.AniListRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The limiter's wait signal (`NetworkThrottle.waitingRequests`), driven with an
 * injected virtual clock and a no-op pause so the tests are instant and
 * deterministic. `WINDOW_LIMIT` requests fit a fresh window; the next one waits.
 */
class AniListRateLimiterTest {

    private val windowLimit = 28

    /** A limiter whose "now" is [clock] and whose wait advances [clock] instead of sleeping. */
    private fun virtualLimiter(
        clock: TestTimeSource,
        observed: MutableList<Int>,
        limit: Int = windowLimit,
    ): AniListRateLimiter {
        lateinit var limiter: AniListRateLimiter
        limiter = AniListRateLimiter(timeSource = clock, windowLimit = limit) { ms ->
            observed += limiter.waitingRequests.value
            clock += ms.milliseconds
        }
        return limiter
    }

    private suspend fun fillWindow(limiter: AniListRateLimiter, limit: Int = windowLimit) {
        repeat(limit) { limiter.withPermit { } }
    }

    @Test
    fun waitingCountIsOneDuringTheWaitAndZeroAfterAdmission() = runBlocking {
        val clock = TestTimeSource()
        val observed = mutableListOf<Int>()
        val limiter = virtualLimiter(clock, observed)
        fillWindow(limiter)

        // The 29th request cannot fit the rolling window: it parks and then admits
        // once the clock crosses the window.
        limiter.withPermit { }

        assertEquals(listOf(1), observed, "the wait signals exactly one parked request")
        assertEquals(0, limiter.waitingRequests.value, "admission clears the count")
    }

    @Test
    fun aSubSecondWaitIsNotSurfaced() = runBlocking {
        val clock = TestTimeSource()
        val observed = mutableListOf<Int>()
        val limiter = virtualLimiter(clock, observed)
        fillWindow(limiter)
        // Slide close to the window's edge so the next wait is under a second.
        clock += 59_100.milliseconds

        limiter.withPermit { }

        assertEquals(listOf(0), observed, "a sub-second wait must not flicker the notice")
        assertEquals(0, limiter.waitingRequests.value)
    }

    @Test
    fun aCustomLimitCapsAtItsOwnWindow() = runBlocking {
        val clock = TestTimeSource()
        val observed = mutableListOf<Int>()
        val limiter = virtualLimiter(clock, observed, limit = 60)
        fillWindow(limiter, limit = 60)

        // The 61st request parks; the default 28 would have parked long ago.
        limiter.withPermit { }

        assertEquals(listOf(1), observed)
        assertEquals(0, limiter.waitingRequests.value)
    }

    @Test
    fun aCancelledWaitLeavesTheCountAtZero() = runBlocking {
        val clock = TestTimeSource()
        val gate = CompletableDeferred<Unit>()
        val limiter = AniListRateLimiter(clock) { gate.await() }
        fillWindow(limiter)

        val job = launch { limiter.withPermit { } }
        withTimeout(5_000) {
            while (limiter.waitingRequests.value == 0) yield()
        }
        assertEquals(1, limiter.waitingRequests.value)

        // Cancellation runs the `finally` that releases the count.
        job.cancelAndJoin()

        assertEquals(0, limiter.waitingRequests.value)
    }

    @Test
    fun aRequestSpendsItsCostInPoints() = runBlocking {
        val clock = TestTimeSource()
        val observed = mutableListOf<Int>()
        val limiter = virtualLimiter(clock, observed)

        // Two 9-point dashboards and ten single queries fill the 28 points.
        limiter.withPermit(9) { }
        limiter.withPermit(9) { }
        repeat(10) { limiter.withPermit { } }
        assertEquals(0, limiter.freePoints())
        assertEquals(emptyList(), observed, "28 points fit one window")

        limiter.withPermit { }
        assertEquals(listOf(1), observed, "the 29th point waits for the window")
    }

    @Test
    fun aDearRequestWaitsUntilEnoughPointsAgeOut() = runBlocking {
        val clock = TestTimeSource()
        val observed = mutableListOf<Int>()
        val limiter = virtualLimiter(clock, observed)
        repeat(20) { limiter.withPermit { } }
        clock += 30_000.milliseconds
        repeat(5) { limiter.withPermit { } }

        // 3 points free, the query needs 10: wait until the first 20 age out,
        // 30 s later, not a full minute.
        val before = clock.markNow()
        limiter.withPermit(10) { }
        val waited = before.elapsedNow().inWholeMilliseconds
        assertEquals(30_001L, waited)
    }

    @Test
    fun theServerRemainingLowersTheFreePoints() = runBlocking {
        val clock = TestTimeSource()
        val limiter = virtualLimiter(clock, mutableListOf())
        limiter.withPermit { }
        assertEquals(27, limiter.freePoints())

        // Budget spent before this install: the server says 12 left (of 30).
        limiter.onResponseHeaders(remaining = 12, resetEpochSeconds = null)
        assertEquals(10, limiter.freePoints(), "server remaining minus the 2-point margin")

        // A higher (stale) remaining never gives points back.
        limiter.onResponseHeaders(remaining = 29, resetEpochSeconds = null)
        assertEquals(10, limiter.freePoints())

        // The synced points age out with the window.
        clock += 60_001.milliseconds
        assertEquals(28, limiter.freePoints())
    }

    @Test
    fun aCostAboveTheWindowSpendsTheWholeWindowInsteadOfHanging() = runBlocking {
        val clock = TestTimeSource()
        val observed = mutableListOf<Int>()
        val limiter = virtualLimiter(clock, observed)
        limiter.withPermit(40) { }
        assertEquals(0, limiter.freePoints())
        assertEquals(emptyList(), observed)
    }
}
