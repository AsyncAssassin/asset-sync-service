package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AccountAndAddressApiIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun cleanBeforeEach() {
        cleanDatabase()
    }

    @AfterEach
    fun cleanAfterEach() {
        cleanDatabase()
    }

    @Test
    fun `create account succeeds`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":" customer-123 "}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", startsWith("/api/v1/accounts/")))
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.externalRef").value("customer-123"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists())
    }

    @Test
    fun `get account succeeds`() {
        val accountId = createAccount("get-account")

        mockMvc.perform(get("/api/v1/accounts/$accountId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(accountId))
            .andExpect(jsonPath("$.externalRef").value("get-account"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `get account returns not found`() {
        val accountId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/accounts/$accountId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Account not found"))
            .andExpect(jsonPath("$.status").value(404))
    }

    @Test
    fun `duplicate externalRef returns conflict`() {
        createAccount("duplicate-account")

        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"duplicate-account"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/duplicate-account"))
            .andExpect(jsonPath("$.title").value("Duplicate account"))
            .andExpect(jsonPath("$.status").value(409))
    }

    @Test
    fun `blank externalRef returns bad request`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"   "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `register watched address succeeds`() {
        val accountId = createAccount("address-registration")

        val response = registerAddress(
            accountId = accountId,
            address = " 0xabc123 ",
            asset = " USDC ",
            label = " primary settlement address ",
        )

        assertEquals(accountId, response["accountId"].asText())
        assertEquals("local-evm", response["chainId"].asText())
        assertEquals("0xabc123", response["address"].asText())
        assertEquals("USDC", response["asset"].asText())
        assertEquals("primary settlement address", response["label"].asText())
        assertEquals("ACTIVE", response["status"].asText())
    }

    @Test
    fun `list watched addresses by account`() {
        val accountId = createAccount("address-list")
        registerAddress(accountId = accountId, address = "0xlist-one", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xlist-two", asset = "ETH")

        val result = mockMvc.perform(get("/api/v1/accounts/$accountId/addresses"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn()

        val items = objectMapper.readTree(result.response.contentAsString)["items"]
        assertEquals(setOf("0xlist-one", "0xlist-two"), items.map { it["address"].asText() }.toSet())
        assertEquals(setOf("USDC", "ETH"), items.map { it["asset"].asText() }.toSet())
    }

    @Test
    fun `account not found on address registration returns not found`() {
        val accountId = UUID.randomUUID()

        mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody()),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Account not found"))
    }

    @Test
    fun `missing and disabled chain registrations return the same unsupported response`() {
        val accountId = createAccount("unsupported-chain")

        expectUnsupportedChain(accountId, "missing-chain")
        insertDisabledChain("disabled-chain")
        expectUnsupportedChain(accountId, "disabled-chain")
    }

    @Test
    fun `duplicate watched address returns conflict`() {
        val firstAccountId = createAccount("duplicate-address-first")
        val secondAccountId = createAccount("duplicate-address-second")
        registerAddress(accountId = firstAccountId, address = "0xduplicate", asset = "USDC")

        mockMvc.perform(
            post("/api/v1/accounts/$secondAccountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody(address = "0xduplicate", asset = "USDC")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/duplicate-watched-address"))
            .andExpect(jsonPath("$.title").value("Duplicate watched address"))
            .andExpect(jsonPath("$.status").value(409))
    }

    @Test
    fun `blank address asset and label validation returns bad request`() {
        val accountId = createAccount("address-validation")

        expectAddressValidationFailure(accountId, addressRequestBody(address = "   "))
        expectAddressValidationFailure(accountId, addressRequestBody(asset = "   "))
        expectAddressValidationFailure(accountId, addressRequestBody(label = "   "))
    }

    private fun createAccount(externalRef: String = "account-${UUID.randomUUID()}"): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"$externalRef"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun registerAddress(
        accountId: String,
        chainId: String = "local-evm",
        address: String = "0x${UUID.randomUUID().toString().replace("-", "")}",
        asset: String = "USDC",
        label: String? = "primary",
    ): JsonNode {
        val result = mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody(chainId, address, asset, label)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().doesNotExist("Location"))
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun expectUnsupportedChain(accountId: String, chainId: String) {
        mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody(chainId = chainId)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Unsupported chain"))
            .andExpect(jsonPath("$.detail").value("Chain configuration was not found or is disabled."))
    }

    private fun expectAddressValidationFailure(accountId: String, body: String) {
        mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
            .andExpect(jsonPath("$.status").value(400))
    }

    private fun addressRequestBody(
        chainId: String = "local-evm",
        address: String = "0xabc123",
        asset: String = "USDC",
        label: String? = "primary",
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "chainId" to chainId,
                "address" to address,
                "asset" to asset,
                "label" to label,
            ),
        )

    private fun insertDisabledChain(chainId: String) {
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO chain_configs (
                chain_id,
                display_name,
                required_confirmations,
                enabled,
                created_at,
                updated_at
            )
            VALUES (?, ?, 3, false, ?, ?)
            """.trimIndent(),
            chainId,
            "Disabled Chain",
            now,
            now,
        )
    }

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id <> 'local-evm'")
        jdbcTemplate.update("UPDATE chain_configs SET enabled = true WHERE chain_id = 'local-evm'")
    }
}
