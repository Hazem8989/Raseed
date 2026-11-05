package com.hazem.dailystore.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRelaySendRateLimiterTest {
    @Test fun capsRollingWindowAndReleasesCapacityAtWindowBoundary() {
        var now = 1_000L
        val limiter = SyncRelaySendRateLimiter(50, 10_000L) { now }
        repeat(50) { assertTrue("message ${it + 1} should fit the budget", limiter.tryAcquire()) }
        assertFalse("the 51st message must be deferred", limiter.tryAcquire())
        now += 9_999L
        assertFalse("the rolling window has not expired", limiter.tryAcquire())
        now += 1L
        assertTrue("capacity must be restored at the 10-second boundary", limiter.tryAcquire())
    }

    @Test fun usesRollingRatherThanFixedWindowAccounting() {
        var now = 0L
        val limiter = SyncRelaySendRateLimiter(2, 10_000L) { now }
        assertTrue(limiter.tryAcquire())
        now = 5_000L
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        now = 10_000L
        assertTrue("only the first timestamp has expired", limiter.tryAcquire())
        assertFalse("the second timestamp is still inside the rolling window", limiter.tryAcquire())
    }
}
