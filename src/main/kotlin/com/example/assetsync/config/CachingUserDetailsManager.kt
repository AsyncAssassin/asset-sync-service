package com.example.assetsync.config

import com.example.assetsync.application.isDatabaseFailure
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.provisioning.UserDetailsManager

/**
 * The user store behind HTTP Basic, which every authenticated request reads, with a short cache in
 * front of the database.
 *
 * A user loaded within [freshFor] comes from memory; an older one is read again, so a password
 * changed or a user removed directly in the database takes effect within that time, and a write
 * through this manager takes effect at once. When the database cannot serve that read, a user loaded
 * within [keepOnOutageFor] is served as it was and the next read waits another [freshFor]: during an
 * outage, requests with credentials do not each wait for the connection pool, and the metrics stay
 * reachable. Only users the store found are cached, so the cache holds the real accounts.
 *
 * Callers always get a copy: Spring Security erases the password of the authenticated principal,
 * which would otherwise erase the cached one.
 */
class CachingUserDetailsManager(
    private val delegate: UserDetailsManager,
    private val clock: Clock,
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
    private val keepOnOutageFor: Duration = DEFAULT_KEEP_ON_OUTAGE_FOR,
) : UserDetailsManager {

    private class Entry(val user: UserDetails, val loadedAt: Instant, @Volatile var checkedAt: Instant)

    private val logger = LoggerFactory.getLogger(CachingUserDetailsManager::class.java)
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun loadUserByUsername(username: String): UserDetails {
        val now = clock.instant()
        val cached = cache[username]
        if (cached != null && now.isBefore(cached.checkedAt.plus(freshFor))) {
            return copyOf(cached.user)
        }
        val loaded = try {
            delegate.loadUserByUsername(username)
        } catch (exception: UsernameNotFoundException) {
            cache.remove(username)
            throw exception
        } catch (exception: RuntimeException) {
            if (cached == null || !exception.isDatabaseFailure() || !now.isBefore(cached.loadedAt.plus(keepOnOutageFor))) {
                throw exception
            }
            cached.checkedAt = now
            logger.warn(
                "user_store_unavailable_serving_cached_user loadedAt={} error={}",
                cached.loadedAt,
                exception.javaClass.simpleName,
            )
            return copyOf(cached.user)
        }
        cache[username] = Entry(user = copyOf(loaded), loadedAt = now, checkedAt = now)
        return loaded
    }

    override fun createUser(user: UserDetails) {
        delegate.createUser(user)
        cache.remove(user.username)
    }

    override fun updateUser(user: UserDetails) {
        delegate.updateUser(user)
        cache.remove(user.username)
    }

    override fun deleteUser(username: String) {
        delegate.deleteUser(username)
        cache.remove(username)
    }

    /** Changes the password of the current user, whom the delegate finds in the security context. */
    override fun changePassword(oldPassword: String?, newPassword: String?) {
        delegate.changePassword(oldPassword, newPassword)
        cache.clear()
    }

    override fun userExists(username: String): Boolean = delegate.userExists(username)

    private fun copyOf(user: UserDetails): UserDetails = User.withUserDetails(user).build()

    companion object {
        val DEFAULT_FRESH_FOR: Duration = Duration.ofSeconds(60)
        val DEFAULT_KEEP_ON_OUTAGE_FOR: Duration = Duration.ofMinutes(10)
    }
}
