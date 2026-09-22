package com.example.assetsync.e2e

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * Real-servlet boot smoke: boots an actual embedded Tomcat via RANDOM_PORT with metrics export
 * enabled (`@AutoConfigureObservability`), matching production, and asserts the Prometheus scrape
 * exposes application meters.
 *
 * WHY `@AutoConfigureObservability` matters (verified 2026-07-02): Spring Boot disables metrics
 * export in tests by default. Without it, Boot's auto-configured `prometheus` endpoint is absent,
 * so a duplicate `@Endpoint(id="prometheus")` collision that crashes `java -jar` (and the Docker
 * ENTRYPOINT) does NOT surface under `@SpringBootTest`. Enabling observability makes this test
 * boot the same endpoint wiring as production, so it now reproduces that startup collision. The
 * CI "Boot smoke" (`java -jar`) step remains the belt-and-braces guard.
 */
@ActiveProfiles("local")
@Import(TestcontainersConfiguration::class)
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "asset-sync.outbox.scheduler.enabled=false",
        "asset-sync.outbox.retention.enabled=false",
        "asset-sync.sync.recovery.enabled=false",
        "asset-sync.sync.worker.enabled=false",
    ],
)
class RealBootStartupSmokeTest(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val context: ApplicationContext,
) {

    @Test
    fun `application boots under a real servlet container and serves actuator endpoints`() {
        val health = restTemplate.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, health.statusCode, "unexpected health response: ${health.body}")
        // `local` shows component details to everyone (show-details: always), including the fake provider.
        assertTrue(health.body?.contains("\"components\"") == true, "local health must expose components")
        assertTrue(health.body?.contains("fakeChainProvider") == true, "local health must include the fake provider indicator")

        val prometheus = restTemplate.getForEntity("/actuator/prometheus", String::class.java)
        assertEquals(HttpStatus.OK, prometheus.statusCode)
        assertTrue(
            prometheus.body?.contains("asset_sync") == true,
            "Prometheus scrape must expose application meters (asset_sync_*).",
        )

        // `local` keeps the fake provider whatever asset-sync.provider.type says: no HTTP bridge or
        // Alchemy bean is created, so neither base-url nor an API key is required here.
        assertEquals(1, context.getBeansOfType(ChainProviderPort::class.java).size)
        assertTrue(context.getBean(ChainProviderPort::class.java) is FakeChainProvider)
        assertTrue(context.getBeansOfType(HttpChainProvider::class.java).isEmpty())
        assertTrue(context.getBeansOfType(AlchemyChainProvider::class.java).isEmpty())
        assertFalse(context.containsBean("chainProviderRestClient"))
        assertFalse(context.containsBean("alchemyRestClient"))
    }
}
