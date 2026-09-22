package com.example.assetsync.unit

import com.example.assetsync.MutableClock
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRateLimiter
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/** Locks the token bucket: burst up to capacity, then one token per refill interval, never past a deadline. */
class AlchemyRateLimiterTests {

    private val clock = MutableClock()
    private val sleeps = mutableListOf<Duration>()
    private val limiter = AlchemyRateLimiter(
        capacity = 2,
        refillPerSecond = 1.0,
        clock = clock,
        sleeper = { duration ->
            sleeps += duration
            clock.advance(duration)
        },
    )

    @Test
    fun `bursts up to capacity then waits exactly one refill interval per token`() {
        limiter.acquire()
        limiter.acquire()
        assertEquals(emptyList(), sleeps, "the first two tokens are the burst")
        assertEquals(0.0, limiter.availableTokens())

        limiter.acquire()
        assertEquals(listOf(Duration.ofSeconds(1)), sleeps)

        clock.advance(Duration.ofSeconds(10))
        assertEquals(2.0, limiter.availableTokens(), "refill is capped at capacity")
    }

    @Test
    fun `a deadline that cannot be met fails as a retryable outage without sleeping`() {
        limiter.acquire()
        limiter.acquire()

        val exception = assertThrows<ChainProviderUnavailableException> {
            limiter.acquire(deadline = clock.instant().plus(Duration.ofMillis(500)))
        }

        assertTrue(exception.message!!.contains("rate limiter deadline exceeded"), exception.message)
        assertEquals(emptyList(), sleeps)

        limiter.acquire(deadline = clock.instant().plus(Duration.ofSeconds(2)))
        assertEquals(listOf(Duration.ofSeconds(1)), sleeps, "a reachable deadline waits for the refill")
    }
}
