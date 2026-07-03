package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Guards N10: the request correlation id is not only echoed on the response, it actually reaches the
 * application log lines (via the `%X{requestId}` pattern) — the whole point of F17, which the
 * remediation had left half-done (id echoed but absent from logs).
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class RequestIdLoggingIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    @Test
    fun `request id is echoed and tagged onto log lines`(output: CapturedOutput) {
        val requestId = "e2e-log-${UUID.randomUUID()}"
        val accountId = createAccount()
        registerAddress(accountId, "0xreqid-log")

        mockMvc.perform(
            post("/api/v1/observed-events")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(observedEventBody("0xreqid-log")),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("X-Request-Id", requestId))

        assertTrue(
            output.all.contains("req=$requestId"),
            "a log line emitted during the request should carry the request id",
        )
    }

    @Test
    fun `an oversized or hostile request id is replaced with a generated one`() {
        val hostile = "y".repeat(500)
        val echoed = mockMvc.perform(
            get("/api/v1/accounts/00000000-0000-0000-0000-000000000000")
                .header("X-Request-Id", hostile),
        ).andReturn().response.getHeader("X-Request-Id")

        assertNotNull(echoed)
        assertNotEquals(hostile, echoed)
        assertTrue(echoed!!.length <= 128, "request id must be bounded")
    }

    private fun createAccount(): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"reqid-${UUID.randomUUID()}"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun registerAddress(accountId: String, address: String) {
        mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("chainId" to "local-evm", "address" to address, "asset" to "USDC", "label" to "reqid"),
                    ),
                ),
        )
            .andExpect(status().isCreated)
    }

    private fun observedEventBody(address: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "chainId" to "local-evm",
                "txHash" to "0xreqid-tx",
                "eventIndex" to 0,
                "address" to address,
                "asset" to "USDC",
                "amount" to "1.000000000000000000",
                "blockHeight" to 100,
                "confirmations" to 1,
                "direction" to "INBOUND",
                "status" to "SEEN",
            ),
        )
}
