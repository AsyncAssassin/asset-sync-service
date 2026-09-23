package com.example.assetsync.e2e

import com.example.assetsync.AssetSyncServiceApplication
import java.net.InetAddress
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * `local` has no authentication and `demo` has public passwords, so started on a host both listen
 * on loopback only. `SERVER_ADDRESS` opens them, as the Docker image does inside its container.
 */
class LoopbackBindingIntegrationTests {

    @Test
    fun `local and demo listen on loopback unless SERVER_ADDRESS opens them`() {
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("asset_sync_binding")
            .withUsername("asset_sync")
            .withPassword("asset_sync")
            .also { it.start() }
        try {
            listOf("local", "demo").forEach { profile ->
                boot(postgres, profile).use { context -> assertEquals("127.0.0.1", boundAddress(context), profile) }
            }
            boot(postgres, "demo", "--SERVER_ADDRESS=0.0.0.0").use { context ->
                assertEquals("0.0.0.0", boundAddress(context))
            }
        } finally {
            postgres.stop()
        }
    }

    private fun boundAddress(context: ConfigurableApplicationContext): String? {
        val webServer = (context as ServletWebServerApplicationContext).webServer as TomcatWebServer
        return (webServer.tomcat.connector.getProperty("address") as InetAddress?)?.hostAddress
    }

    private fun boot(
        postgres: PostgreSQLContainer<*>,
        profile: String,
        vararg extraArgs: String,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(AssetSyncServiceApplication::class.java)
            .profiles(profile)
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--asset-sync.outbox.scheduler.enabled=false",
                "--asset-sync.outbox.retention.enabled=false",
                "--asset-sync.sync.recovery.enabled=false",
                "--asset-sync.sync.worker.enabled=false",
                *extraArgs,
            )
}
