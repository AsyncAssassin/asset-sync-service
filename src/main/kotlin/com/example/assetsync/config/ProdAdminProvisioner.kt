package com.example.assetsync.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.UserDetailsManager

/**
 * Provisions the production operator account from env secrets and fails the startup fast if they
 * are absent — so prod never silently falls back to a generated password. Runs only under `prod`
 * (demo/e2e seed their own users), so no default credentials ever live in the repo.
 */
@Configuration
@Profile("prod")
class ProdAdminProvisioner {

    private val logger = LoggerFactory.getLogger(ProdAdminProvisioner::class.java)

    @Bean
    fun provisionProdAdmin(
        environment: Environment,
        userDetailsManager: UserDetailsManager,
        passwordEncoder: PasswordEncoder,
    ): ApplicationRunner =
        ApplicationRunner {
            val username = environment.getProperty("ASSET_SYNC_ADMIN_USERNAME")
            val password = environment.getProperty("ASSET_SYNC_ADMIN_PASSWORD")
            require(!username.isNullOrBlank()) {
                "ASSET_SYNC_ADMIN_USERNAME must be set in the prod profile."
            }
            require(!password.isNullOrBlank()) {
                "ASSET_SYNC_ADMIN_PASSWORD must be set in the prod profile."
            }
            // The demo-user guard refuses these names on every later start, so the admin cannot take one.
            require(username !in setOf(DEMO_READER_USERNAME, DEMO_OPERATOR_USERNAME)) {
                "ASSET_SYNC_ADMIN_USERNAME must not be a demo user name ($DEMO_READER_USERNAME, $DEMO_OPERATOR_USERNAME)."
            }
            val admin = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("OPERATOR")
                .build()
            // Apply the env password on every boot so rotating the secret takes effect on restart.
            if (userDetailsManager.userExists(username)) {
                userDetailsManager.updateUser(admin)
                logger.info("prod_admin_updated username={}", username)
            } else {
                userDetailsManager.createUser(admin)
                logger.info("prod_admin_provisioned username={}", username)
            }
        }
}
