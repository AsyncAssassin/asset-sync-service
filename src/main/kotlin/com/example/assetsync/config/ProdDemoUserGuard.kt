package com.example.assetsync.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Refuses to start a protected profile on a database that holds the well-known `demo` users.
 * `DemoDataSeeder` writes them into the same `users` table every protected profile reads, so a
 * database or volume that once ran `demo` would otherwise accept the public demo passwords. The
 * guard covers `prod` and every other protected profile (`e2e`, or a custom one such as `staging`);
 * only `demo` itself and the unauthenticated `local` and `test` skip it. The check runs while the
 * context starts, after the migrations and before the web server opens its port. It never changes
 * the data: the message names the users and the SQL that removes them.
 */
@Component
@Profile("!demo & !local & !test")
class ProdDemoUserGuard(
    private val jdbcTemplate: JdbcTemplate,
    private val environment: Environment,
) : InitializingBean {

    override fun afterPropertiesSet() {
        val demoUsers = jdbcTemplate.queryForList(
            "SELECT username FROM users WHERE username IN (?, ?) ORDER BY username",
            String::class.java,
            DEMO_OPERATOR_USERNAME,
            DEMO_READER_USERNAME,
        )
        check(demoUsers.isEmpty()) {
            val names = demoUsers.joinToString(", ") { "'$it'" }
            "The active profiles ${environment.activeProfiles.toList()} found the demo users $demoUsers in the user store; " +
                "their passwords are public. Never share a database between the demo profile and a protected one. " +
                "Remove them before starting: " +
                "DELETE FROM authorities WHERE username IN ($names); DELETE FROM users WHERE username IN ($names);"
        }
    }
}
