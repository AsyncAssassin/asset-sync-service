package com.example.assetsync.unit

import com.example.assetsync.config.LoopbackBindGuard
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * An empty `server.address` binds no address, so Tomcat listens on every interface. `local` and
 * `demo`, which listen on loopback by default, refuse it; every other value and profile passes.
 */
class LoopbackBindGuardTests {

    @Test
    fun `local and demo refuse an empty address and name the profile`() {
        listOf("", " ").forEach { address ->
            listOf("local", "demo").forEach { profile ->
                val violation = LoopbackBindGuard.violation(listOf(profile), address)
                assertNotNull(violation, "$profile with '$address' must be refused")
                assertTrue(violation.contains("make $profile listen on every interface"), violation)
            }
        }
    }

    @Test
    fun `the loopback default, a named interface, and other profiles pass`() {
        listOf("127.0.0.1", "0.0.0.0", "::1", null).forEach { address ->
            assertNull(LoopbackBindGuard.violation(listOf("local"), address), "local with $address must start")
        }
        listOf(listOf("prod"), listOf("test"), listOf("e2e"), listOf("default")).forEach { profiles ->
            assertNull(LoopbackBindGuard.violation(profiles, ""), "$profiles has no loopback default to lose")
        }
    }
}
