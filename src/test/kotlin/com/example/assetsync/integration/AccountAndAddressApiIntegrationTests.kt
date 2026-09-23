package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.api.dto.MAX_ADDRESS_LENGTH
import com.example.assetsync.api.dto.MAX_ASSET_LENGTH
import com.example.assetsync.api.dto.MAX_EXTERNAL_REF_LENGTH
import com.example.assetsync.api.dto.MAX_LABEL_LENGTH
import com.example.assetsync.application.account.WatchedAddressApplicationService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
    fun `an oversized externalRef that ends in a line break gets only its length error`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("externalRef" to "a".repeat(99_999) + "\n"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(
                jsonPath("$.errors[0]").value("externalRef: externalRef must be at most $MAX_EXTERNAL_REF_LENGTH characters"),
            )
    }

    @Test
    fun `a yaml body is refused like any other content type that is not json`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType("application/yaml")
                .content("externalRef: yaml-account\n"),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/unsupported-media-type"))

        assertEquals(0, tableCount("accounts"))
    }

    @Test
    fun `problem responses echo request id`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .header("X-Request-Id", "request-id-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"   "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("X-Request-Id", "request-id-test"))
            .andExpect(jsonPath("$.requestId").value("request-id-test"))
    }

    @Test
    fun `unknown route returns not found problem detail`() {
        mockMvc.perform(get("/api/v1/nothing-here").header("X-Request-Id", "unknown-route-test"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.instance").value("/api/v1/nothing-here"))
            .andExpect(jsonPath("$.requestId").value("unknown-route-test"))
    }

    @Test
    fun `unsupported method returns method not allowed problem detail with allow header`() {
        mockMvc.perform(delete("/api/v1/accounts"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", "POST"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/method-not-allowed"))
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.supportedMethods[0]").value("POST"))
    }

    @Test
    fun `unsupported content type returns unsupported media type problem detail`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.TEXT_PLAIN)
                .content("externalRef=customer-123"),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/unsupported-media-type"))
            .andExpect(jsonPath("$.status").value(415))
    }

    @Test
    fun `unacceptable accept header keeps the framework status instead of internal error`() {
        val accountId = createAccount("not-acceptable-test")

        mockMvc.perform(get("/api/v1/accounts/$accountId").accept(MediaType.TEXT_XML))
            .andExpect(status().isNotAcceptable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-acceptable"))
            .andExpect(jsonPath("$.status").value(406))
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
        registerAddress(accountId = accountId, address = "0xlist-two", asset = "USDC")

        val result = mockMvc.perform(get("/api/v1/accounts/$accountId/addresses"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andReturn()

        val items = objectMapper.readTree(result.response.contentAsString)["items"]
        assertEquals(setOf("0xlist-one", "0xlist-two"), items.map { it["address"].asText() }.toSet())
        assertEquals(listOf("USDC", "USDC"), items.map { it["asset"].asText() })
    }

    @Test
    fun `list watched addresses supports bounded pagination`() {
        val accountId = createAccount("address-list-page")
        registerAddress(accountId = accountId, address = "0xlist-page-one", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xlist-page-two", asset = "USDC")

        mockMvc.perform(get("/api/v1/accounts/$accountId/addresses?page=0&size=1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.hasNext").value(true))

        mockMvc.perform(get("/api/v1/accounts/$accountId/addresses?page=1&size=1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    @Test
    fun `list watched addresses rejects negative page`() {
        val accountId = createAccount("address-list-negative-page")

        mockMvc.perform(get("/api/v1/accounts/$accountId/addresses?page=-1&size=50"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-pagination"))
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `list watched addresses rejects huge page before database offset`() {
        val accountId = createAccount("address-list-huge-page")
        val hugePage = WatchedAddressApplicationService.MAX_PAGE + 1

        mockMvc.perform(get("/api/v1/accounts/$accountId/addresses?page=$hugePage&size=100"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-pagination"))
            .andExpect(jsonPath("$.maxPage").value(WatchedAddressApplicationService.MAX_PAGE))
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
    fun `local evm watched address identity is normalized before uniqueness check`() {
        val firstAccountId = createAccount("normalized-address-first")
        val secondAccountId = createAccount("normalized-address-second")

        val response = registerAddress(
            accountId = firstAccountId,
            address = " 0xABCDEF ",
            asset = " usdc ",
        )

        assertEquals("0xabcdef", response["address"].asText())
        assertEquals("USDC", response["asset"].asText())

        mockMvc.perform(
            post("/api/v1/accounts/$secondAccountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody(address = "0xabcdef", asset = "USDC")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/duplicate-watched-address"))
    }

    @Test
    fun `blank address asset and label validation returns bad request`() {
        val accountId = createAccount("address-validation")

        expectAddressValidationFailure(accountId, addressRequestBody(address = "   "))
        expectAddressValidationFailure(accountId, addressRequestBody(asset = "   "))
        expectAddressValidationFailure(accountId, addressRequestBody(label = "   "))
    }

    @Test
    fun `oversized account and watched address fields return bad request`() {
        mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"${"x".repeat(MAX_EXTERNAL_REF_LENGTH + 1)}"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))

        val accountId = createAccount("address-length-validation")
        expectAddressValidationFailure(accountId, addressRequestBody(address = "x".repeat(MAX_ADDRESS_LENGTH + 1)))
        expectAddressValidationFailure(accountId, addressRequestBody(asset = "x".repeat(MAX_ASSET_LENGTH + 1)))
        expectAddressValidationFailure(accountId, addressRequestBody(label = "x".repeat(MAX_LABEL_LENGTH + 1)))

        assertEquals(1, tableCount("accounts"))
        assertEquals(0, tableCount("watched_addresses"))
    }

    @Test
    fun `unknown asset on an enabled chain is rejected as unsupported asset`() {
        val accountId = createAccount("asset-unknown")

        expectUnsupportedAsset(accountId = accountId, chainId = "local-evm", asset = "DAI")
    }

    @Test
    fun `disabled asset on an enabled chain is rejected as unsupported asset`() {
        val accountId = createAccount("asset-disabled")
        insertDisabledAssetConfig(chainId = "local-evm", asset = "DAI")

        expectUnsupportedAsset(accountId = accountId, chainId = "local-evm", asset = "DAI")
    }

    @Test
    fun `disabled chain is rejected before the asset is checked`() {
        val accountId = createAccount("asset-chain-disabled")

        // eth-mainnet is seeded disabled together with its disabled USDC registry row.
        expectUnsupportedChain(accountId = accountId, chainId = "eth-mainnet")
    }

    @Test
    fun `seeded sepolia usdc registers and normalizes evm casing`() {
        val accountId = createAccount("asset-sepolia")

        val response = registerAddress(
            accountId = accountId,
            chainId = "eth-sepolia",
            address = "0xABCDEF0123456789ABCDEF0123456789ABCDEF01",
            asset = "usdc",
        )

        assertEquals("eth-sepolia", response["chainId"].asText())
        assertEquals("0xabcdef0123456789abcdef0123456789abcdef01", response["address"].asText())
        assertEquals("USDC", response["asset"].asText())
    }

    @Test
    fun `sepolia registration rejects addresses that are not 0x and 40 hex digits`() {
        val accountId = createAccount("address-format-sepolia")

        expectInvalidAddress(accountId, "eth-sepolia", "0x123")
            .andExpect(jsonPath("$.detail").value("address must be 0x followed by 40 hex digits on eth-sepolia."))
        expectInvalidAddress(accountId, "eth-sepolia", "0x" + "g".repeat(40))
        expectInvalidAddress(accountId, "eth-sepolia", "0x" + "a".repeat(41))
        expectInvalidAddress(accountId, "eth-sepolia", "0xabcdef0123456789abcd\nf0123456789abcdef01")
            .andExpect(jsonPath("$.detail").value("address must not contain control characters."))

        assertEquals(0, tableCount("watched_addresses"))
    }

    @Test
    fun `local evm registration keeps synthetic addresses but rejects whitespace slashes colons and control characters`() {
        val accountId = createAccount("address-format-local")

        listOf("0xab cd", "0xabc/def", "0xab:cd", "0xab\ncd").forEach { address ->
            expectInvalidAddress(accountId, "local-evm", address)
        }
        assertEquals(0, tableCount("watched_addresses"))

        assertEquals("0xsynthetic-demo", registerAddress(accountId = accountId, address = "0xSynthetic-Demo")["address"].asText())
    }

    @Test
    fun `watched address status can be disabled and enabled again`() {
        val accountId = createAccount("address-status")
        val address = registerAddress(accountId = accountId, address = "0xstatus-toggle")
        val addressId = address["id"].asText()

        patchStatus(addressId, "DISABLED")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(addressId))
            .andExpect(jsonPath("$.status").value("DISABLED"))
        mockMvc.perform(get("/api/v1/accounts/$accountId/addresses"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].status").value("DISABLED"))
        mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isNotFound)
        val disabledUpdatedAt = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM watched_addresses WHERE id = ?",
            Timestamp::class.java,
            UUID.fromString(addressId),
        )

        // Setting the current status again changes nothing, not even updated_at.
        patchStatus(addressId, "DISABLED").andExpect(status().isOk)
        assertEquals(
            disabledUpdatedAt,
            jdbcTemplate.queryForObject("SELECT updated_at FROM watched_addresses WHERE id = ?", Timestamp::class.java, UUID.fromString(addressId)),
        )

        patchStatus(addressId, "ACTIVE")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
        mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isAccepted)
    }

    @Test
    fun `watched address status update rejects unknown addresses and statuses`() {
        patchStatus(UUID.randomUUID().toString(), "DISABLED")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Watched address not found"))

        val addressId = registerAddress(accountId = createAccount("address-status-invalid"), address = "0xstatus-invalid")["id"].asText()
        listOf("PAUSED", "disabled", "").forEach { value ->
            patchStatus(addressId, value)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
        }
        assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT status FROM watched_addresses WHERE id = ?", String::class.java, UUID.fromString(addressId)))
    }

    private fun patchStatus(addressId: String, value: String) =
        mockMvc.perform(
            patch("/api/v1/addresses/$addressId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("status" to value))),
        )

    private fun expectInvalidAddress(accountId: String, chainId: String, address: String) =
        mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody(chainId = chainId, address = address)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-request"))
            .andExpect(jsonPath("$.chainId").value(chainId))

    private fun expectUnsupportedAsset(accountId: String, chainId: String, asset: String) {
        mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressRequestBody(chainId = chainId, asset = asset)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Unsupported asset"))
            .andExpect(jsonPath("$.detail").value("Asset configuration was not found or is disabled for the chain."))
            .andExpect(jsonPath("$.chainId").value(chainId))
            .andExpect(jsonPath("$.asset").value(asset))
    }

    private fun insertDisabledAssetConfig(chainId: String, asset: String) {
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO asset_configs (
                chain_id, asset, token_standard, contract_address, decimals, display_name, enabled, created_at, updated_at
            )
            VALUES (?, ?, 'ERC20', ?, 18, NULL, false, ?, ?)
            """.trimIndent(),
            chainId,
            asset,
            "0x" + "d".repeat(40),
            now,
            now,
        )
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
            .andExpect(jsonPath("$.detail").value("The chain is not configured, is disabled, or is not served by the active provider."))
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

    private fun tableCount(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM asset_configs WHERE asset <> 'USDC'")
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id NOT IN ('local-evm', 'eth-sepolia', 'eth-mainnet')")
        jdbcTemplate.update("UPDATE chain_configs SET enabled = true WHERE chain_id = 'local-evm'")
    }
}
