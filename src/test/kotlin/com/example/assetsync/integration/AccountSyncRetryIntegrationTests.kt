package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.AccountSyncPass
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The retries of an account pass under budgets large enough for a whole pass in one claim: an
 * address may fetch several pages, and the retry list can fill up. Backoffs are a millisecond, so
 * every retry is due at the next claim.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.account-sync-batch-size=100",
        "asset-sync.sync.max-account-sync-addresses=200",
        "asset-sync.sync.pagination.max-pages-per-account-run=500",
        "asset-sync.sync.pagination.max-pages-per-address-run=5",
        "asset-sync.sync.pagination.continuation-requeue-delay=0s",
        "asset-sync.sync.pagination.cursor-lease-retry-delay=0s",
        "asset-sync.sync.worker.max-attempts=3",
        "asset-sync.sync.worker.retry-backoff-base-delay=1ms",
        "asset-sync.sync.worker.retry-backoff-max-delay=1ms",
    ],
)
@AutoConfigureMockMvc
class AccountSyncRetryIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
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
    fun `an address that commits pages before it fails is progressing, not failing, and breaks a streak`() {
        val accountId = createAccount()
        val firstId = registerAddress(accountId, "0xprogress-first")
        val progressingId = registerAddress(accountId, "0xprogress-heavy")
        val thirdId = registerAddress(accountId, "0xprogress-third")
        val fourthId = registerAddress(accountId, "0xprogress-fourth")
        scriptTimeout("0xprogress-first")
        // One page is committed, then the next fetch times out, as on a heavy address.
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xprogress-heavy",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Event(providerEvent("0xprogress-heavy-1", "0xprogress-heavy")),
                FakeChainProviderStep.Failure(TIMEOUT),
            ),
        )
        scriptTimeout("0xprogress-third")
        scriptTimeout("0xprogress-fourth")

        val syncRunId = submitAccountSync(accountId)
        runNextClaim()

        // Four failures, but the committed page between them keeps them apart: no outage.
        assertEquals("QUEUED", runStatus(syncRunId))
        assertEquals("ADDRESS_RETRY", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(
            mapOf(firstId to 1, progressingId to 0, thirdId to 1, fourthId to 1),
            accountPass(syncRunId)["retries"].associate { it["watchedAddressId"].asText() to it["failedAttempts"].asInt() },
        )
    }

    @Test
    fun `a full retry list holds the scan at the next failing address until the retries drain it`() {
        val accountId = createAccount()
        val count = AccountSyncPass.MAX_RETRIES + 1
        var lastSyncedId = ""
        (0 until count).forEach { index ->
            lastSyncedId = registerAddress(accountId, "0xfull-ok-$index")
            scriptOneEvent("0xfull-ok-$index")
            registerAddress(accountId, "0xfull-failing-$index")
            scriptTimeout("0xfull-failing-$index")
        }

        val syncRunId = submitAccountSync(accountId)
        runNextClaim()

        // The failure past the full list neither fails the claim nor is skipped.
        assertEquals("QUEUED", runStatus(syncRunId))
        assertEquals("ADDRESS_RETRY", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", syncRunId))
        val pass = accountPass(syncRunId)
        assertEquals(AccountSyncPass.MAX_RETRIES, pass["retries"].size())
        assertEquals(lastSyncedId, pass["afterId"].asText())
        assertEquals(count, tableCount("observed_transactions"))

        (0 until count).forEach { scriptOneEvent("0xfull-failing-$it") }
        runNextClaim()

        assertEquals("SUCCEEDED", runStatus(syncRunId))
        assertEquals(2 * count, tableCount("observed_transactions"))
    }

    private fun scriptOneEvent(address: String) {
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = address,
            asset = "USDC",
            events = listOf(providerEvent(txHash = "$address-1", address = address)),
        )
    }

    private fun scriptTimeout(address: String) {
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = address,
            asset = "USDC",
            steps = listOf(FakeChainProviderStep.Failure(TIMEOUT)),
        )
    }

    private fun providerEvent(txHash: String, address: String): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = 0,
            address = address,
            asset = "USDC",
            amount = BigDecimal("1.000000000000000000"),
            blockHeight = 100,
            confirmations = 1,
            direction = Direction.INBOUND,
            status = TransactionStatus.SEEN,
        )

    private fun createAccount(): String =
        objectMapper.readTree(
            mockMvc.perform(
                post("/api/v1/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"externalRef":"account-${UUID.randomUUID()}"}"""),
            )
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString,
        )["id"].asText()

    private fun registerAddress(accountId: String, address: String): String =
        objectMapper.readTree(
            mockMvc.perform(
                post("/api/v1/accounts/$accountId/addresses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"chainId":"local-evm","address":"$address","asset":"USDC"}"""),
            )
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString,
        )["id"].asText()

    private fun submitAccountSync(accountId: String): UUID =
        UUID.fromString(
            objectMapper.readTree(
                mockMvc.perform(post("/api/v1/accounts/$accountId/sync"))
                    .andExpect(status().isAccepted)
                    .andReturn().response.contentAsString,
            )["id"].asText(),
        )

    /** Claims the next due run, waiting the millisecond of a retry backoff for it to come due. */
    private fun runNextClaim() {
        val deadline = System.nanoTime() + 2_000_000_000L
        var claimed = syncRunLifecycleService.claimDueRuns(workerId = "test-worker-${UUID.randomUUID()}", limit = 1)
        while (claimed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5)
            claimed = syncRunLifecycleService.claimDueRuns(workerId = "test-worker-${UUID.randomUUID()}", limit = 1)
        }
        assertEquals(1, claimed.size, "expected one due sync run")
        syncApplicationService.executeClaimedSyncRun(claimed.single())
    }

    private fun runStatus(syncRunId: UUID): String =
        singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId)

    private fun accountPass(syncRunId: UUID): JsonNode =
        objectMapper.readTree(singleString("SELECT run_checkpoint::text FROM sync_runs WHERE id = ?", syncRunId))["accountPass"]

    private fun tableCount(table: String): Int =
        singleInt("SELECT count(*) FROM $table")

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun cleanDatabase() {
        fakeChainProvider.clear()
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update(
            "UPDATE chain_configs SET enabled = true, required_confirmations = 3, updated_at = ? WHERE chain_id = 'local-evm'",
            Timestamp.from(Instant.now()),
        )
    }

    private companion object {
        const val TIMEOUT = "Provider timeout after PT10S."
    }
}
