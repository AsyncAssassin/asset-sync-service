package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncCursorRepository
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderKey
import com.example.assetsync.infrastructure.provider.FakeChainProviderPage
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Account sync passes that do not fit one claim. The claim budget is two provider pages and the
 * per-address budget one page, so a three-address account needs two claims and an address with a
 * second page needs a claim of its own. Requeue delays are zero so the next claim is due at once.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.account-sync-batch-size=2",
        "asset-sync.sync.max-account-sync-addresses=10",
        "asset-sync.sync.pagination.max-pages-per-account-run=2",
        "asset-sync.sync.pagination.max-pages-per-address-run=1",
        "asset-sync.sync.pagination.continuation-requeue-delay=0s",
        "asset-sync.sync.pagination.cursor-lease-retry-delay=0s",
    ],
)
@AutoConfigureMockMvc
class AccountSyncIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
    @Autowired private val syncCursorRepository: SyncCursorRepository,
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
    fun `a pass larger than one claim resumes where it stopped and completes`() {
        val accountId = createAccount()
        val addresses = listOf("0xpass-one", "0xpass-two", "0xpass-three")
        addresses.forEach { address ->
            registerAddress(accountId, address)
            scriptOneEvent(address)
        }

        val syncRunId = submitAccountSync(accountId)
        runNextClaim()

        assertEquals("QUEUED", runStatus(syncRunId))
        assertEquals("CONTINUATION", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, tableCount("observed_transactions"))

        runNextClaim()

        assertEquals("SUCCEEDED", runStatus(syncRunId))
        assertEquals(3, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT continuation_count FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(3, tableCount("observed_transactions"))
        // Every address was fetched exactly once: the second claim did not start the pass over.
        assertEquals(addresses.map { key(it) }, fakeChainProvider.requestedKeys())
    }

    @Test
    fun `an address registered between claims joins the running pass`() {
        val accountId = createAccount()
        listOf("0xjoin-one", "0xjoin-two", "0xjoin-three").forEach { address ->
            registerAddress(accountId, address)
            scriptOneEvent(address)
        }

        val syncRunId = submitAccountSync(accountId)
        runNextClaim()
        registerAddress(accountId, "0xjoin-late")
        scriptOneEvent("0xjoin-late")
        runNextClaim()

        assertEquals("SUCCEEDED", runStatus(syncRunId))
        assertEquals(4, tableCount("observed_transactions"))
        assertEquals(1, singleInt("SELECT count(*) FROM observed_transactions WHERE address = '0xjoin-late'"))
    }

    @Test
    fun `an address with pages left keeps the pass on it until it is drained`() {
        val accountId = createAccount()
        registerAddress(accountId, "0xdrain-long")
        registerAddress(accountId, "0xdrain-short")
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xdrain-long",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(page(expectedCursor = null, txHash = "0xdrain-long-1", blockHeight = 100, nextCursor = "long-2", hasMore = true)),
                FakeChainProviderStep.Page(page(expectedCursor = "long-2", txHash = "0xdrain-long-2", blockHeight = 101, nextCursor = "long-final", hasMore = false)),
            ),
        )
        scriptOneEvent("0xdrain-short")

        val syncRunId = submitAccountSync(accountId)
        runNextClaim()
        assertEquals("QUEUED", runStatus(syncRunId))
        assertEquals(1, tableCount("observed_transactions"))

        runNextClaim()

        assertEquals("SUCCEEDED", runStatus(syncRunId))
        assertEquals(3, tableCount("observed_transactions"))
        assertEquals(listOf(key("0xdrain-long"), key("0xdrain-long"), key("0xdrain-short")), fakeChainProvider.requestedKeys())
    }

    @Test
    fun `a busy address is revisited after the scan instead of restarting the pass`() {
        val accountId = createAccount()
        val busyAddressId = UUID.fromString(registerAddress(accountId, "0xrevisit-busy"))
        registerAddress(accountId, "0xrevisit-free")
        scriptOneEvent("0xrevisit-busy")
        scriptOneEvent("0xrevisit-free")
        val now = Instant.now()
        val busyToken = UUID.randomUUID()
        syncCursorRepository.ensureCursor(watchedAddressId = busyAddressId, now = now)
        assertNotNull(
            syncCursorRepository.tryAcquireCursorLease(
                watchedAddressId = busyAddressId,
                lockedBy = "external-worker",
                lockToken = busyToken,
                now = now,
                leaseUntil = now.plusSeconds(60),
            ),
        )

        val syncRunId = submitAccountSync(accountId)
        runNextClaim()

        assertEquals("QUEUED", runStatus(syncRunId))
        assertEquals("LEASE_BUSY", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(listOf(key("0xrevisit-free")), fakeChainProvider.requestedKeys())

        syncCursorRepository.releaseCursorLeaseFenced(
            watchedAddressId = busyAddressId,
            lockedBy = "external-worker",
            lockToken = busyToken,
            updatedAt = Instant.now(),
        )
        runNextClaim()

        assertEquals("SUCCEEDED", runStatus(syncRunId))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(listOf(key("0xrevisit-free"), key("0xrevisit-busy")), fakeChainProvider.requestedKeys())
    }

    @Test
    fun `an address that fails terminally is reported without stopping the others, and disabling it lets the account sync pass`() {
        val accountId = createAccount()
        registerAddress(accountId, "0xisolate-one")
        val brokenAddressId = registerAddress(accountId, "0xisolate-broken")
        registerAddress(accountId, "0xisolate-three")
        scriptOneEvent("0xisolate-one")
        // An event for another address is provider data invalid: terminal for this address.
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xisolate-broken",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xisolate-broken-1", address = "0xnot-this-address")),
        )
        scriptOneEvent("0xisolate-three")

        val failedRunId = submitAccountSync(accountId)
        runNextClaim()

        assertEquals("FAILED", runStatus(failedRunId))
        val lastError = singleString("SELECT last_error FROM sync_runs WHERE id = ?", failedRunId)
        assertTrue(lastError.startsWith("1 of 3 addresses failed terminally: $brokenAddressId: "), lastError)
        assertTrue(lastError.contains("wrong watched address"), lastError)
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(1, singleInt("SELECT count(*) FROM observed_transactions WHERE address = '0xisolate-three'"))

        mockMvc.perform(
            patch("/api/v1/addresses/$brokenAddressId")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"DISABLED"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DISABLED"))

        val retryRunId = submitAccountSync(accountId)
        runNextClaim()

        assertEquals("SUCCEEDED", runStatus(retryRunId))
        assertEquals(2, tableCount("observed_transactions"))
    }

    private fun scriptOneEvent(address: String) {
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = address,
            asset = "USDC",
            events = listOf(providerEvent(txHash = "$address-1", address = address)),
        )
    }

    private fun page(expectedCursor: String?, txHash: String, blockHeight: Long, nextCursor: String, hasMore: Boolean) =
        FakeChainProviderPage(
            expectedCursor = expectedCursor,
            events = listOf(providerEvent(txHash = txHash, address = "0xdrain-long", blockHeight = blockHeight)),
            nextCursor = nextCursor,
            hasMore = hasMore,
            latestBlockHeight = blockHeight,
            safeBlockHeight = blockHeight,
        )

    private fun providerEvent(txHash: String, address: String, blockHeight: Long = 100): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = 0,
            address = address,
            asset = "USDC",
            amount = BigDecimal("1.000000000000000000"),
            blockHeight = blockHeight,
            confirmations = 1,
            direction = Direction.INBOUND,
            status = TransactionStatus.SEEN,
        )

    private fun key(address: String) = FakeChainProviderKey("local-evm", address, "USDC")

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

    private fun runNextClaim() {
        val claimed = syncRunLifecycleService.claimDueRuns(workerId = "test-worker-${UUID.randomUUID()}", limit = 1)
        assertEquals(1, claimed.size, "expected one due sync run")
        syncApplicationService.executeClaimedSyncRun(claimed.single())
    }

    private fun runStatus(syncRunId: UUID): String =
        singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId)

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
}
