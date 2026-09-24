package com.example.assetsync.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Refuses to start `local` or `demo` with an empty `server.address`. Both listen on 127.0.0.1
 * unless `SERVER_ADDRESS` names another interface, but a variable that is set and empty replaces
 * that default with an empty value, which binds as no address at all: the server would listen on
 * every interface, `local` without authentication and `demo` with its public passwords.
 *
 * Registered in `META-INF/spring.factories` next to [ProfileCombinationGuard], so it runs once the
 * profile's configuration is loaded and before the server binds.
 */
class LoopbackBindGuard : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        violation(ProfileCombinationGuard.effectiveProfiles(environment), environment.getProperty("server.address"))
            ?.let { throw IllegalStateException(it) }
    }

    companion object {
        /** Profiles that listen on loopback unless `SERVER_ADDRESS` names another interface. */
        val LOOPBACK_PROFILES: Set<String> = setOf("local", "demo")

        /** Why [address] cannot serve [profiles], or null when it can. */
        fun violation(profiles: List<String>, address: String?): String? {
            val loopback = profiles.filter { it in LOOPBACK_PROFILES }
            if (loopback.isEmpty() || address == null || address.isNotBlank()) {
                return null
            }
            return "server.address is set but empty, for example by an empty SERVER_ADDRESS, which would make " +
                "${loopback.joinToString(" and ")} listen on every interface. Unset it to listen on 127.0.0.1, " +
                "or set the interface to listen on, such as 0.0.0.0."
        }
    }
}
