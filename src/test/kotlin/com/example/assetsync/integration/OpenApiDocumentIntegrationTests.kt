package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
        assertEquals("#/components/schemas/AccountResponse", createAccount.path("content").path("application/json").path("schema").path("\$ref").asText())
        listOf(
            createAccount,
            paths.path("/api/v1/addresses/{addressId}/sync").path("post").path("responses").path("202"),
            paths.path("/api/v1/accounts/{accountId}/sync").path("post").path("responses").path("202"),
        ).forEach { response ->
            // Always sent, as a relative reference.
            val location = response.path("headers").path(HttpHeaders.LOCATION)
            assertTrue(location.path("required").asBoolean(), response.toString())
            assertEquals("uri-reference", location.path("schema").path("format").asText(), response.toString())
        }
    }

    @Test
    fun `every operation refers to the shared problem detail errors, and only mutations to the 403`() {
        val document = apiDocs()
        val components = document.path("components")

        val problemDetail = components.path("schemas").path("ProblemDetail")
        assertEquals("uri-reference", problemDetail.path("properties").path("instance").path("format").asText())
        assertEquals("array", problemDetail.path("properties").path("errors").path("type").asText())
        val shared = mapOf("400" to "BadRequest", "401" to "Unauthorized", "403" to "Forbidden", "500" to "InternalError", "503" to "DatabaseUnavailable")
        shared.values.forEach { name ->
            val schema = components.path("responses").path(name).path("content").path("application/problem+json").path("schema")
            assertEquals("#/components/schemas/ProblemDetail", schema.path("\$ref").asText(), name)
        }
        operations(document.path("paths")).forEach { (name, operation) ->
            val responses = operation.path("responses")
            shared.filterKeys { it != "403" }.forEach { (status, component) ->
                assertEquals("#/components/responses/$component", responses.path(status).path("\$ref").asText(), "$name $status")
            }
            // READ may call every GET; only a change of state needs OPERATOR.
            assertEquals(!name.startsWith("GET "), responses.has("403"), "$name 403")
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

    @Test
    fun `request patterns stay portable, and a field keeps its one pattern`() {
        val schemas = apiDocs().path("components").path("schemas")

        // The control character rule is a validator of its own: no Java-only regex reaches the document.
        listOf("IngestObservedEventRequest", "RegisterWatchedAddressRequest", "CreateAccountRequest").forEach { name ->
            val patterns = schemas.path(name).path("properties").properties().mapNotNull { (_, property) -> property.path("pattern").textValue() }
            assertTrue(patterns.none { "\\p{" in it }, "$name: $patterns")
        }
        assertEquals("(?s).*\\S.*", schemas.path("CreateAccountRequest").path("properties").path("externalRef").path("pattern").asText())
        assertEquals("(?s).*\\S.*", schemas.path("RegisterWatchedAddressRequest").path("properties").path("label").path("pattern").asText())
    }

    private fun apiDocs(): JsonNode {
        val body = mockMvc.perform(get("/v3/api-docs").headers(HttpHeaders().apply { setBasicAuth("demo-reader", "demo-reader-pw") }))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun operations(paths: JsonNode): Map<String, JsonNode> =
        paths.properties().flatMap { (path, item) ->
            item.properties().map { (method, operation) -> "${method.uppercase()} $path" to operation }
        }.toMap()
}
