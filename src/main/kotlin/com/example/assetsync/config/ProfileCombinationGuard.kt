package com.example.assetsync.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment

/**
 * Refuses to start when `demo`, `local`, or `test` is combined with any other profile. `demo`
 * seeds users with public passwords and a fake dataset and opens the chain simulator; `local` and
 * `test` turn authentication off. Each of them runs alone, so none of that reaches `prod`, or a
 * custom deployment profile such as `staging`, through a profile list like `prod,demo` or
 * `staging,local`.
 *
 * As an environment post-processor (registered in `META-INF/spring.factories`) the guard runs once
 * the active profiles are known and before the application context exists: before any bean, any
 * configuration condition, or any placeholder of the other profiles is resolved, so the message
 * names the profiles instead of whichever missing bean or secret the combination trips over first.
 */
class ProfileCombinationGuard : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        violation(effectiveProfiles(environment))?.let { throw IllegalStateException(it) }
    }

    companion object {
        /** Profiles that must be the only active one. */
        val STANDALONE_PROFILES: Set<String> = setOf("demo", "local", "test")

        /** Why [profiles] cannot run together, or null when they can. */
        fun violation(profiles: List<String>): String? {
            val standalone = profiles.filter { it in STANDALONE_PROFILES }
            if (standalone.isEmpty() || profiles.size == 1) {
                return null
            }
            return "The profiles $profiles cannot be combined: ${standalone.joinToString(" and ")} " +
                "must be the only active profile. demo seeds users with public passwords and opens the chain " +
                "simulator, and local and test turn authentication off, so none of them may run together with " +
                "another profile such as prod."
        }

        /** The active profiles, or the default ones when none is active, as Spring resolves them. */
        fun effectiveProfiles(environment: Environment): List<String> =
            environment.activeProfiles.toList().ifEmpty { environment.defaultProfiles.toList() }
    }
}
