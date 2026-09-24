package com.example.assetsync.unit

import com.example.assetsync.MutableClock
import com.example.assetsync.config.CachingUserDetailsManager
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.provisioning.InMemoryUserDetailsManager

/**
 * The user store HTTP Basic reads on every request: fresh for a minute, written through at once,
 * and kept for ten minutes of database outage, so authenticated requests do not each wait for the
 * connection pool while the database is down.
 */
class CachingUserDetailsManagerTests {

    private val clock = MutableClock()
    private val store = CountingStore()
    private val manager = CachingUserDetailsManager(delegate = store, clock = clock)

    @Test
    fun `a user is read from the database once a minute`() {
        manager.loadUserByUsername("operator")
        clock.advance(Duration.ofSeconds(59))
        manager.loadUserByUsername("operator")
        assertEquals(1, store.loads)

        clock.advance(Duration.ofSeconds(2))
        manager.loadUserByUsername("operator")
        assertEquals(2, store.loads, "a password changed in the database applies within a minute")
    }

    @Test
    fun `callers get a copy, so erasing the principal leaves the cache intact`() {
        (manager.loadUserByUsername("operator") as CredentialsContainer).eraseCredentials()
        (manager.loadUserByUsername("operator") as CredentialsContainer).eraseCredentials()

        assertEquals("{noop}operator-pw", manager.loadUserByUsername("operator").password)
        assertEquals(1, store.loads)
    }

    @Test
    fun `a write through the manager applies at once`() {
        manager.loadUserByUsername("operator")
        manager.updateUser(User.withUsername("operator").password("{noop}rotated").roles("OPERATOR").build())

        assertEquals("{noop}rotated", manager.loadUserByUsername("operator").password)
        assertEquals(2, store.loads)

        manager.deleteUser("operator")
        assertThrows<UsernameNotFoundException> { manager.loadUserByUsername("operator") }
    }

    @Test
    fun `a database outage serves the last loaded user for ten minutes and asks the database once a minute`() {
        manager.loadUserByUsername("operator")
        store.failure = DataAccessResourceFailureException("Connection refused")

        clock.advance(Duration.ofSeconds(61))
        assertEquals("{noop}operator-pw", manager.loadUserByUsername("operator").password)
        assertEquals(2, store.loads, "the stale user was read again and the read failed")

        clock.advance(Duration.ofSeconds(30))
        manager.loadUserByUsername("operator")
        assertEquals(2, store.loads, "within a minute of the failed read the database is left alone")

        clock.advance(Duration.ofSeconds(31))
        manager.loadUserByUsername("operator")
        assertEquals(3, store.loads)

        clock.advance(Duration.ofMinutes(9))
        val expired = assertThrows<DataAccessResourceFailureException> { manager.loadUserByUsername("operator") }
        assertNotNull(expired.message)
    }

    @Test
    fun `an outage is no reason to accept a user the cache never held, and other failures are not hidden`() {
        store.failure = DataAccessResourceFailureException("Connection refused")
        assertThrows<DataAccessResourceFailureException> { manager.loadUserByUsername("operator") }

        store.failure = null
        manager.loadUserByUsername("operator")
        store.failure = IllegalStateException("not a database failure")
        clock.advance(Duration.ofSeconds(61))
        assertThrows<IllegalStateException> { manager.loadUserByUsername("operator") }
    }

    @Test
    fun `an unknown user is not cached`() {
        assertThrows<UsernameNotFoundException> { manager.loadUserByUsername("nobody") }
        assertThrows<UsernameNotFoundException> { manager.loadUserByUsername("nobody") }

        assertEquals(2, store.loads)
    }

    /** An in-memory store that counts reads and can fail them like a database that is down. */
    private class CountingStore : InMemoryUserDetailsManager(
        User.withUsername("operator").password("{noop}operator-pw").roles("OPERATOR").build(),
    ) {
        var loads = 0
        var failure: RuntimeException? = null

        override fun loadUserByUsername(username: String): UserDetails {
            loads += 1
            failure?.let { throw it }
            return super.loadUserByUsername(username)
        }
    }
}
