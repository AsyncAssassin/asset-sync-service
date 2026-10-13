package com.example.assetsync.e2e

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.ResolvableType
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.View
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.view.xslt.XsltView
import org.springframework.web.servlet.view.xslt.XsltViewResolver

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
    fun `Spring security scan exceptions require DTO responses without XSLT or SSE`() {
        // Reassess the two CVEs in .trivyignore before adding view rendering or streaming responses.
        assertTrue(context.getBeansOfType(XsltView::class.java).isEmpty())
        assertTrue(context.getBeansOfType(XsltViewResolver::class.java).isEmpty())
        val mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
        val handlers = mapping.handlerMethods.values.filter {
            it.beanType.packageName.startsWith("com.example.assetsync.")
        }
        assertTrue(handlers.isNotEmpty())
        handlers.forEach { handler ->
            val message = "Reassess .trivyignore for ${handler.beanType.simpleName}.${handler.method.name}"
            assertTrue(AnnotatedElementUtils.hasAnnotation(handler.beanType, RestController::class.java), message)
            val returnType = ResolvableType.forMethodReturnType(handler.method)
            val bodyType = if (HttpEntity::class.java.isAssignableFrom(handler.returnType.parameterType)) {
                returnType.getGeneric(0).resolve()
            } else {
                returnType.resolve()
            }
            assertNotNull(bodyType, message)
            assertTrue(bodyType.packageName.startsWith("com.example.assetsync.") && bodyType.kotlin.isData, message)
            assertFalse(View::class.java.isAssignableFrom(bodyType), message)
            assertFalse(ResponseBodyEmitter::class.java.isAssignableFrom(bodyType), message)
        }
    }

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
