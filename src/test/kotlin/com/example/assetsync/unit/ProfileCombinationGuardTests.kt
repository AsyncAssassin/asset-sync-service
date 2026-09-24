package com.example.assetsync.unit

import com.example.assetsync.config.ProdDemoUserGuard
import com.example.assetsync.config.ProfileCombinationGuard
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.env.Profiles
import org.springframework.mock.env.MockEnvironment

/**
 * The profile matrix behind the two startup guards: `ProfileCombinationGuard` lets `demo`, `local`,
 * and `test` run only alone, and `ProdDemoUserGuard` runs in every remaining profile set, so no
 * profile list can seed or accept the public demo users outside `demo`.
 */
class ProfileCombinationGuardTests {

    @Test
    fun `standalone profiles run alone and every other set is allowed`() {
        listOf(
            listOf("prod"),
            listOf("demo"),
            listOf("local"),
            listOf("test"),
            listOf("e2e"),
            listOf("staging"),
            listOf("default"),
            listOf("prod", "staging"),
        ).forEach { profiles ->
            assertNull(ProfileCombinationGuard.violation(profiles), "$profiles must be allowed")
        }
    }

    @Test
    fun `demo, local, or test combined with any other profile is refused and named`() {
        listOf(
            listOf("prod", "demo"),
            listOf("prod", "local"),
            listOf("prod", "test"),
            listOf("staging", "demo"),
            listOf("staging", "local"),
            listOf("e2e", "test"),
            listOf("demo", "local"),
        ).forEach { profiles ->
            val violation = ProfileCombinationGuard.violation(profiles)
            assertNotNull(violation, "$profiles must be refused")
            assertTrue(violation.startsWith("The profiles $profiles cannot be combined"), violation)
        }
    }

    @Test
    fun `the refusal names the default profile when none is active`() {
        val environment = MockEnvironment()

        assertEquals(listOf("default"), ProfileCombinationGuard.effectiveProfiles(environment))
    }

    @Test
    fun `the demo-user guard covers every profile set the combination guard lets start, except demo`() {
        val expression = requireNotNull(AnnotationUtils.findAnnotation(ProdDemoUserGuard::class.java, Profile::class.java)).value
        val guarded = { profiles: List<String> ->
            MockEnvironment().apply { setActiveProfiles(*profiles.toTypedArray()) }.acceptsProfiles(Profiles.of(*expression))
        }

        listOf(listOf("prod"), listOf("e2e"), listOf("staging"), listOf("prod", "staging"), emptyList()).forEach { profiles ->
            assertTrue(guarded(profiles), "$profiles must check for the demo users")
        }
        listOf(listOf("demo"), listOf("local"), listOf("test")).forEach { profiles ->
            assertTrue(!guarded(profiles), "$profiles must not check for the demo users")
        }
    }
}
