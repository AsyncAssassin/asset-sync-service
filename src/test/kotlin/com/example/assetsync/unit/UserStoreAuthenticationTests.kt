package com.example.assetsync.unit

import com.example.assetsync.MutableClock
import com.example.assetsync.config.CachingUserDetailsManager
import com.example.assetsync.config.UserStoreAuthenticationProvider
import com.example.assetsync.config.UserStoreCache
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.provisioning.InMemoryUserDetailsManager

/**
 * HTTP Basic through the user cache, as the protected chain wires it: a user is read from the store
 * once a minute, a changed password is picked up at the first mismatch, writes through the service
 * apply at once, and while the store is down a user read in the last ten minutes stays accepted,
 * without telling which names those are.
 */
class UserStoreAuthenticationTests {

    private val clock = MutableClock()
    private val store = CountingStore()
    private val cache = UserStoreCache(clock)
    private val manager = CachingUserDetailsManager(delegate = store, cache = cache)
    private val provider = UserStoreAuthenticationProvider(manager, PasswordEncoderFactories.createDelegatingPasswordEncoder(), cache)

    @Test
    fun `a user is read from the store once a minute`() {
        authenticate("operator", "operator-pw")
        clock.advance(Duration.ofSeconds(59))
        authenticate("operator", "operator-pw")
        assertEquals(1, store.loads)

        clock.advance(Duration.ofSeconds(2))
        authenticate("operator", "operator-pw")
        assertEquals(2, store.loads)
    }

    @Test
    fun `a password changed elsewhere works at once, and then the old one no longer does`() {
        authenticate("operator", "operator-pw")
        // Another instance, or SQL, changes the password: this cache never hears of it.
        store.updateUser(user("operator", "rotated"))

        authenticate("operator", "rotated")
        assertEquals(2, store.loads, "the mismatch read the store again")
        assertThrows<BadCredentialsException> { authenticate("operator", "operator-pw") }
    }

    @Test
    fun `a write through the service applies at once`() {
        authenticate("operator", "operator-pw")
        manager.updateUser(user("operator", "rotated"))

        assertThrows<BadCredentialsException> { authenticate("operator", "operator-pw") }
        authenticate("operator", "rotated")
    }

    @Test
    fun `a load that raced a write does not bring back the old password`() {
        // The load reads the old row, then the write commits and empties the cache before the load stores.
        store.afterRead = { manager.updateUser(user("operator", "rotated")) }
        authenticate("operator", "operator-pw")
        store.afterRead = {}

        assertThrows<BadCredentialsException> { authenticate("operator", "operator-pw") }
        authenticate("operator", "rotated")
    }

    @Test
    fun `callers get a copy, so erasing the principal leaves the cache intact`() {
        (authenticate("operator", "operator-pw").principal as CredentialsContainer).eraseCredentials()

        authenticate("operator", "operator-pw")
        assertEquals(1, store.loads)
    }

    @Test
    fun `during an outage a user read in the last ten minutes stays accepted, and the store is asked once a minute`() {
        authenticate("operator", "operator-pw")
        store.failure = DataAccessResourceFailureException("Connection refused")

        clock.advance(Duration.ofSeconds(61))
        authenticate("operator", "operator-pw")
        assertEquals(2, store.loads, "the stale user was read again and the read failed")
        clock.advance(Duration.ofSeconds(30))
        authenticate("operator", "operator-pw")
        assertEquals(2, store.loads, "within a minute of the failed read the store is left alone")

        // A failed read never stretches the ten minutes.
        clock.advance(Duration.ofSeconds(8 * 60 + 28))
        authenticate("operator", "operator-pw")
        clock.advance(Duration.ofSeconds(2))
        val expired = assertThrows<InternalAuthenticationServiceException> { authenticate("operator", "operator-pw") }
        assertTrue(expired.cause is DataAccessResourceFailureException)
    }

    @Test
    fun `during an outage a wrong password for a cached user gets the answer of an unknown name`() {
        authenticate("operator", "operator-pw")
        store.failure = DataAccessResourceFailureException("Connection refused")

        val wrongPassword = assertThrows<InternalAuthenticationServiceException> { authenticate("operator", "wrong") }
        val unknownName = assertThrows<InternalAuthenticationServiceException> { authenticate("nobody", "wrong") }

        assertTrue(wrongPassword.cause is DataAccessResourceFailureException)
        assertTrue(unknownName.cause is DataAccessResourceFailureException)
        authenticate("operator", "operator-pw")
    }

    @Test
    fun `while one request reads a user again, the others get the cached copy instead of waiting for the store`() {
        authenticate("operator", "operator-pw")
        clock.advance(Duration.ofSeconds(61))
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterRead = {
            if (store.loads == 2) {
                reading.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        val first = CompletableFuture.supplyAsync { authenticate("operator", "operator-pw") }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        authenticate("operator", "operator-pw")
        assertEquals(2, store.loads, "only the first request reads the store")

        release.countDown()
        first.get(5, TimeUnit.SECONDS)
        authenticate("operator", "operator-pw")
        assertEquals(2, store.loads, "the user read again is fresh for another minute")
    }

    @Test
    fun `an unknown user is not cached`() {
        assertThrows<BadCredentialsException> { authenticate("nobody", "pw") }
        assertThrows<BadCredentialsException> { authenticate("nobody", "pw") }

        assertEquals(2, store.loads)
    }

    private fun authenticate(username: String, password: String): Authentication =
        provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password))

    private fun user(username: String, password: String): UserDetails =
        User.withUsername(username).password("{noop}$password").roles("OPERATOR").build()

    /** An in-memory store that counts reads, can fail them like a database that is down, and can act between reading and returning. */
    private class CountingStore : InMemoryUserDetailsManager(
        User.withUsername("operator").password("{noop}operator-pw").roles("OPERATOR").build(),
    ) {
        @Volatile
        var loads = 0

        @Volatile
        var failure: RuntimeException? = null

        @Volatile
        var afterRead: () -> Unit = {}

        override fun loadUserByUsername(username: String): UserDetails {
            loads += 1
            failure?.let { throw it }
            val user = super.loadUserByUsername(username)
            afterRead()
            return user
        }
    }
}
