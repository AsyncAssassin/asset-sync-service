package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.sync.ChainProviderUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.min

/**
 * Local token bucket in front of every Alchemy JSON-RPC call: one token per request, refilled
 * continuously. It exists to prevent 429s rather than to map them, because every 429 costs one of
 * a run's failure attempts. A caller with a deadline never sleeps past it; the wait becomes a
 * retryable outage instead, and the checkpoint stays put.
 */
class AlchemyRateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis().coerceAtLeast(1)) },
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerSecond > 0.0 && refillPerSecond.isFinite()) { "refillPerSecond must be positive" }
    }

    private val lock = Any()
    private var tokens: Double = capacity.toDouble()
    private var lastRefill: Instant = clock.instant()

    /** Takes one token, sleeping for the refill when the bucket is empty; `deadline` bounds the sleep. */
    fun acquire(deadline: Instant? = null) {
        while (true) {
            val wait = synchronized(lock) {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return
                }
                val missing = 1.0 - tokens
                Duration.ofNanos(ceil(missing / refillPerSecond * NANOS_PER_SECOND).toLong())
            }
            if (deadline != null && clock.instant().plus(wait).isAfter(deadline)) {
                throw ChainProviderUnavailableException("Alchemy local rate limiter deadline exceeded.", throttled = true)
            }
            try {
                sleeper(wait)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ChainProviderUnavailableException("Alchemy local rate limiter wait interrupted.", exception)
            }
        }
    }

    /** Tokens currently available, for diagnostics and tests. */
    fun availableTokens(): Double = synchronized(lock) {
        refill()
        tokens
    }

    private fun refill() {
        val now = clock.instant()
        val elapsed = Duration.between(lastRefill, now)
        if (!elapsed.isNegative && !elapsed.isZero) {
            tokens = min(capacity.toDouble(), tokens + elapsed.toNanos() / NANOS_PER_SECOND * refillPerSecond)
        }
        lastRefill = now
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
