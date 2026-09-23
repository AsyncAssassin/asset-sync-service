package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Drives the `demo` chain simulator through MockMvc with the protected security chain in place.
 * `demo` is the only profile that opens the simulator path to anonymous callers, because the HTTP
 * provider calls it without credentials; the simulator's request validation answers with the
 * API's ProblemDetail shape.
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
class ChainSimulatorIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    @Test
    fun `demo keeps the simulator open to anonymous callers`() {
        mockMvc.perform(eventsRequest(limit = "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
    }

    @Test
    fun `a non-positive limit is a 400 problem detail instead of a 500`() {
        mockMvc.perform(eventsRequest(limit = "0"))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-request"))
            .andExpect(jsonPath("$.instance").value("/simulator/v1/chains/local-evm/addresses/0xdemoaddr/events"))
    }

    @Test
    fun `transaction hashes are well formed and deterministic on every seeded chain`() {
        listOf(
            "local-evm" to "0xdemoaddr",
            "eth-sepolia" to "0x742d35cc6634c0532925a3b844bc454e4438f44e",
        ).forEach { (chainId, address) ->
            val txHash = firstEventTxHash(chainId, address)

            assertTrue(Regex("^0x[0-9a-f]{64}$").matches(txHash), "$chainId returned $txHash")
            assertNull(ChainIdentityNormalizer.txHashViolation(chainId, txHash), "$chainId must accept $txHash")
            assertEquals(txHash, firstEventTxHash(chainId, address), "$chainId must return the same hash again")
        }
    }

    private fun firstEventTxHash(chainId: String, address: String): String {
        val result = mockMvc.perform(eventsRequest(limit = "1", chainId = chainId, address = address))
            .andExpect(status().isOk)
            .andReturn()
        return objectMapper.readTree(result.response.contentAsString)["events"][0]["txHash"].asText()
    }

    private fun eventsRequest(
        limit: String,
        chainId: String = "local-evm",
        address: String = "0xdemoaddr",
    ): MockHttpServletRequestBuilder =
        get("/simulator/v1/chains/$chainId/addresses/$address/events")
            .param("asset", "USDC")
            .param("limit", limit)
}
