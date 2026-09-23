package com.example.assetsync.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Refuses to start `prod` on a database that holds the well-known `demo` users. `DemoDataSeeder`
 * writes them into the same `users` table the prod user store reads, so a database or volume that
 * once ran `demo` would otherwise accept the public demo passwords in prod. The check runs while
 * the context starts, after the migrations and before the web server opens its port. It never
 * changes the data: the message names the users and the SQL that removes them.
 */
@Component
@Profile("prod")
class ProdDemoUserGuard(
    private val jdbcTemplate: JdbcTemplate,
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
            "The prod profile found the demo users $demoUsers in its user store; their passwords are public. " +
                "Never share a database between the demo and prod profiles. Remove them before starting prod: " +
                "DELETE FROM authorities WHERE username IN ($names); DELETE FROM users WHERE username IN ($names);"
        }
    }
}
