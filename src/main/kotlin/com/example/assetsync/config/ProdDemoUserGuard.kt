package com.example.assetsync.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Refuses to start on a database that holds the well-known `demo` users, in every profile except
 * `demo` itself and the unauthenticated `local` and `test`: `prod`, `e2e`, a custom one such as
 * `staging`, and a start without any profile. `DemoDataSeeder` writes those users into the same
 * `users` table every protected profile reads, so a database or volume that once ran `demo` would
 * otherwise accept the public demo passwords. `ProfileCombinationGuard` keeps `demo`, `local`, and
 * `test` from joining another profile, so this check cannot be switched off by adding one of them.
 *
 * The check runs while the context starts, after the migrations where Liquibase runs, and before
 * the web server opens its port. It is never lazy, so `spring.main.lazy-initialization` cannot skip
 * it, and a database without the `users` table (no migrations yet) has no demo users to find. It
 * never changes the data: the message names the users and the SQL that removes them.
 */
@Component
@Lazy(false)
@Profile("!demo & !local & !test")
class ProdDemoUserGuard(
    private val jdbcTemplate: JdbcTemplate,
    private val environment: Environment,
) : InitializingBean {

    private val logger = LoggerFactory.getLogger(ProdDemoUserGuard::class.java)

    override fun afterPropertiesSet() {
        val hasUserStore = jdbcTemplate.queryForObject("SELECT to_regclass('users') IS NOT NULL", Boolean::class.java) == true
        if (!hasUserStore) {
            logger.info("demo_user_guard_skipped reason=no_users_table")
            return
        }
        val demoUsers = jdbcTemplate.queryForList(
            "SELECT username FROM users WHERE username IN (?, ?) ORDER BY username",
            String::class.java,
            DEMO_OPERATOR_USERNAME,
            DEMO_READER_USERNAME,
        )
        check(demoUsers.isEmpty()) {
            val names = demoUsers.joinToString(", ") { "'$it'" }
            "The profiles ${ProfileCombinationGuard.effectiveProfiles(environment)} found the demo users $demoUsers " +
                "in the user store; their passwords are public. This database once ran the demo profile and also holds " +
                "its demo dataset, so the safest fix is an empty database. To keep this one, remove the users before " +
                "starting: DELETE FROM authorities WHERE username IN ($names); DELETE FROM users WHERE username IN ($names);"
        }
    }
}
