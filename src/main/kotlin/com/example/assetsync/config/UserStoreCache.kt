package com.example.assetsync.config

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserCache
import org.springframework.security.core.userdetails.UserDetails

/**
 * The users HTTP Basic has read from the user store, as Spring Security's [UserCache].
 *
 * A user read within [freshFor] is served from here without the store. A password that does not
 * match a cached user makes Spring Security read the store again, so a password changed on another
 * instance works at once; the old one works until the user is read again. An older user is read
 * again by one request at a time, while the others get the cached copy instead of each waiting for
 * the store. When the store cannot be read, a user read within [keepOnOutageFor] stands in for it
 * ([outageFallback]) and the next read waits another [freshFor], never past [keepOnOutageFor]: during
 * a database outage requests with credentials do not each wait for the connection pool, and the
 * metrics stay reachable.
 *
 * Only the store's own loads fill the cache ([store]). A write through the service removes the
 * user's entry at once, and a load that raced such a write is not cached. Callers always get a
 * copy: Spring Security erases the password of the authenticated principal, which would otherwise
 * erase the cached one.
 */
class UserStoreCache(
    private val clock: Clock,
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
    private val keepOnOutageFor: Duration = DEFAULT_KEEP_ON_OUTAGE_FOR,
) : UserCache {

    private class Entry(val user: UserDetails, val loadedAt: Instant) {
        @Volatile
        var checkedAt: Instant = loadedAt

        /** Set while one request reads this user from the store again. */
        val rereading = AtomicBoolean(false)
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Any()
    private var writes = 0L

    override fun getUserFromCache(username: String): UserDetails? {
        val entry = entries[username] ?: return null
        val now = clock.instant()
        if (now.isBefore(minOf(entry.checkedAt.plus(freshFor), entry.loadedAt.plus(keepOnOutageFor)))) {
            return copyOf(entry.user)
        }
        // Stale: this request reads the store again, unless another one already does.
        if (now.isBefore(entry.loadedAt.plus(keepOnOutageFor)) && !entry.rereading.compareAndSet(false, true)) {
            return copyOf(entry.user)
        }
        return null
    }

    /** Loads fill the cache through [store], which knows whether a write came in meanwhile. */
    override fun putUserInCache(user: UserDetails) = Unit

    /** A write of [username] through the service: its entry goes, and loads under way are not cached. */
    override fun removeUserFromCache(username: String) {
        synchronized(lock) {
            writes += 1
            entries.remove(username)
        }
    }

    /** A write whose user the service cannot name, such as a password change of the current user. */
    fun removeAll() {
        synchronized(lock) {
            writes += 1
            entries.clear()
        }
    }

    /** The store no longer has [username]; nothing was written, so loads under way stay cacheable. */
    fun forget(username: String) {
        entries.remove(username)
    }

    /** The write count a load starts from, to hand to [store]. */
    fun writeCount(): Long = synchronized(lock) { writes }

    /** Caches [user] as read now, unless a write came in since the load began at [writesBefore]. */
    fun store(username: String, user: UserDetails, writesBefore: Long) {
        synchronized(lock) {
            if (writes == writesBefore) {
                entries[username] = Entry(copyOf(user), clock.instant())
            }
        }
    }

    /**
     * The cached user that may stand in for a store that cannot be read, now marked as checked, or
     * null when there is none read within [keepOnOutageFor].
     */
    fun outageFallback(username: String): UserDetails? {
        val now = clock.instant()
        val entry = entries[username]?.takeIf { now.isBefore(it.loadedAt.plus(keepOnOutageFor)) } ?: return null
        entry.checkedAt = now
        return copyOf(entry.user)
    }

    /** Ends a read of [username] that [getUserFromCache] left to the calling request. */
    fun rereadDone(username: String) {
        entries[username]?.rereading?.set(false)
    }

    private fun copyOf(user: UserDetails): UserDetails = User.withUserDetails(user).build()

    companion object {
        val DEFAULT_FRESH_FOR: Duration = Duration.ofSeconds(60)
        val DEFAULT_KEEP_ON_OUTAGE_FOR: Duration = Duration.ofMinutes(10)
    }
}
