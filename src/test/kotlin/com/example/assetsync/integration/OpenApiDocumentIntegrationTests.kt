package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The OpenAPI document is what a reviewer opens first, so it must say what the API does: the
 * success code each operation answers with, the ProblemDetail errors every operation shares, and
 * only the fields a client sends. `demo` is the profile that also serves the chain simulator,
 * which must stay out of the document. The configuration matches ChainSimulatorIntegrationTests,
 * so both share one context.
 */
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.provider.base-url=http://localhost:1",
        "asset-sync.outbox.scheduler.enabled=false",
        "asset-sync.sync.recovery.enabled=false",
        "asset-sync.sync.worker.enabled=false",
    ],
)
@AutoConfigureMockMvc
class OpenApiDocumentIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    @Test
    fun `each operation documents the status it answers with, and the simulator stays out`() {
        val document = apiDocs()
        val paths = document.path("paths")

        assertTrue(paths.fieldNames().asSequence().all { it.startsWith("/api/") }, "paths: ${paths.fieldNames().asSequence().toList()}")
        assertEquals(
            mapOf(
                "POST /api/v1/accounts" to listOf("201"),
                "GET /api/v1/accounts/{accountId}" to listOf("200"),
                "POST /api/v1/accounts/{accountId}/addresses" to listOf("201"),
                "GET /api/v1/accounts/{accountId}/addresses" to listOf("200"),
                "PATCH /api/v1/addresses/{addressId}" to listOf("200"),
                "POST /api/v1/addresses/{addressId}/sync" to listOf("202"),
                "POST /api/v1/accounts/{accountId}/sync" to listOf("202"),
                "GET /api/v1/sync-runs/{syncRunId}" to listOf("200"),
                "POST /api/v1/observed-events" to listOf("200", "201"),
            ),
            operations(paths).mapValues { (_, operation) ->
                operation.path("responses").fieldNames().asSequence().filter { it.startsWith("2") }.sorted().toList()
            },
        )
        val createAccount = paths.path("/api/v1/accounts").path("post").path("responses").path("201")
        assertTrue(createAccount.path("headers").has("Location"), createAccount.toString())
        assertTrue(createAccount.path("content").path("application/json").path("schema").path("\$ref").asText().endsWith("/AccountResponse"), createAccount.toString())
        val syncAddress = paths.path("/api/v1/addresses/{addressId}/sync").path("post").path("responses").path("202")
        assertTrue(syncAddress.path("headers").has("Location"), syncAddress.toString())
    }

    @Test
    fun `every operation shares the problem detail errors`() {
        val document = apiDocs()

        assertEquals("object", document.path("components").path("schemas").path("ProblemDetail").path("type").asText())
        operations(document.path("paths")).forEach { (name, operation) ->
            listOf("400", "401", "403", "503").forEach { status ->
                val schema = operation.path("responses").path(status).path("content").path("application/problem+json").path("schema")
                assertEquals("#/components/schemas/ProblemDetail", schema.path("\$ref").asText(), "$name $status")
            }
        }
    }

    @Test
    fun `request schemas list only the fields a client sends`() {
        val schemas = apiDocs().path("components").path("schemas")

        listOf("IngestObservedEventRequest", "UpdateWatchedAddressRequest").forEach { name ->
            val schema = schemas.path(name)
            val fields = schema.path("properties").fieldNames().asSequence().toList() + schema.path("required").map { it.asText() }
            assertTrue(fields.isNotEmpty() && fields.none { it.endsWith("Valid") }, "$name: $schema")
        }
    }

    private fun apiDocs(): JsonNode {
        val credentials = Base64.getEncoder().encodeToString("demo-reader:demo-reader-pw".toByteArray())
        val body = mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, "Basic $credentials"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun operations(paths: JsonNode): Map<String, JsonNode> =
        paths.fields().asSequence().flatMap { (path, item) ->
            item.fields().asSequence().map { (method, operation) -> "${method.uppercase()} $path" to operation }
        }.toMap()
}
