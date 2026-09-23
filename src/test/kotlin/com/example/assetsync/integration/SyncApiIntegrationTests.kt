package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.api.dto.MAX_TX_HASH_LENGTH
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.SyncCursorRepository
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderKey
import com.example.assetsync.infrastructure.provider.FakeChainProviderPage
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.provider-timeout=100ms",
        "asset-sync.sync.provider-max-threads=2",
        "asset-sync.sync.account-sync-batch-size=1",
        "asset-sync.sync.max-account-sync-addresses=2",
        "asset-sync.sync.worker.max-concurrency=2",
        "asset-sync.sync.worker.retry-backoff-base-delay=1s",
        "asset-sync.sync.worker.retry-backoff-max-delay=1s",
    ],
)
@AutoConfigureMockMvc
class SyncApiIntegrationTests(
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
    fun `address sync POST returns accepted quickly and worker records changed provider events`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-address-success")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-address-success",
            asset = "USDC",
            events = listOf(
                providerEvent(txHash = "0xsync-address-success-1", address = "0xsync-address-success"),
                providerEvent(txHash = "0xsync-address-success-2", address = "0xsync-address-success"),
            ),
        )

        val startedAt = System.nanoTime()
        val syncRunId = submitAddressSync(addressId)
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(elapsed < Duration.ofMillis(500), "POST should only enqueue; elapsed=$elapsed")
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertNull(nullableTimestamp("SELECT started_at FROM sync_runs WHERE id = ?", syncRunId))

        runNextClaimedSyncs()

        mockMvc.perform(get("/api/v1/sync-runs/$syncRunId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(syncRunId.toString()))
            .andExpect(jsonPath("$.targetType").value("ADDRESS"))
            .andExpect(jsonPath("$.targetId").value(addressId))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.eventsSeen").value(2))
            .andExpect(jsonPath("$.eventsChanged").value(2))
            .andExpect(jsonPath("$.queuedAt").exists())
            .andExpect(jsonPath("$.startedAt").exists())
            .andExpect(jsonPath("$.finishedAt").exists())

        assertEquals(1, tableCount("sync_runs"))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
        assertEquals(listOf("provider:fake"), jdbcTemplate.queryForList("SELECT DISTINCT source FROM observed_transactions", String::class.java))
        assertEquals(
            listOf("provider:fake"),
            jdbcTemplate.queryForList("SELECT DISTINCT payload ->> 'source' FROM outbox_events", String::class.java),
        )
        assertEquals(
            listOf(FakeChainProviderKey("local-evm", "0xsync-address-success", "USDC")),
            fakeChainProvider.requestedKeys(),
        )
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `address sync consumes multiple provider pages and persists final cursor`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-three-pages")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-three-pages",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(providerEvent(txHash = "0xsync-three-pages-1", address = "0xsync-three-pages", blockHeight = 100)),
                        nextCursor = "page-2",
                        hasMore = true,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = "page-2",
                        events = listOf(providerEvent(txHash = "0xsync-three-pages-2", address = "0xsync-three-pages", blockHeight = 101)),
                        nextCursor = "page-3",
                        hasMore = true,
                        latestBlockHeight = 101,
                        safeBlockHeight = 101,
                    ),
                ),
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = "page-3",
                        events = listOf(providerEvent(txHash = "0xsync-three-pages-3", address = "0xsync-three-pages", blockHeight = 102)),
                        nextCursor = "final-cursor",
                        hasMore = false,
                        latestBlockHeight = 102,
                        safeBlockHeight = 102,
                    ),
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(3, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(3, tableCount("observed_transactions"))
        assertEquals(listOf(null, "page-2", "page-3"), fakeChainProvider.requestedPageRequests().map { it.cursor })
        assertEquals(
            "final-cursor",
            singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)),
        )
    }

    @Test
    fun `empty final page with block high water and no cursor succeeds and advances checkpoint`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-empty-high-water")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-empty-high-water",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                        latestBlockHeight = 500,
                    ),
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertNull(nullableString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertNull(nullableLong("SELECT last_processed_block_height FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertEquals(500L, singleLong("SELECT last_finalized_block_height FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
    }

    @Test
    fun `empty final high water page preserves event checkpoint, cursor, and updates finalized high water`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-high-water-after-cursor")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-high-water-after-cursor",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(
                            providerEvent(
                                txHash = "0xsync-high-water-after-cursor-1",
                                address = "0xsync-high-water-after-cursor",
                                eventIndex = 5,
                                blockHeight = 100,
                            ),
                        ),
                        nextCursor = "page-2",
                        hasMore = true,
                        latestBlockHeight = 100,
                        safeBlockHeight = 90,
                    ),
                ),
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = "page-2",
                        events = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                        latestBlockHeight = 125,
                        safeBlockHeight = 120,
                    ),
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        // The empty final page omitted its cursor; the stored one still points right after the
        // last processed event, so a bridge that resumes only by cursor keeps working.
        assertEquals(
            "page-2",
            singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)),
        )
        assertEquals(100L, singleLong("SELECT last_processed_block_height FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertEquals(5, singleInt("SELECT last_processed_event_index FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertEquals(120L, singleLong("SELECT last_finalized_block_height FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
    }

    @Test
    fun `final page with events and no cursor clears the cursor and the next fetch resumes from the checkpoint`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-final-events-no-cursor")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-final-events-no-cursor",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(
                            providerEvent(txHash = "0xsync-final-events-1", address = "0xsync-final-events-no-cursor", blockHeight = 100),
                        ),
                        nextCursor = "page-2",
                        hasMore = true,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = "page-2",
                        events = listOf(
                            providerEvent(txHash = "0xsync-final-events-2", address = "0xsync-final-events-no-cursor", blockHeight = 101),
                            providerEvent(txHash = "0xsync-final-events-3", address = "0xsync-final-events-no-cursor", eventIndex = 4, blockHeight = 102),
                        ),
                        nextCursor = null,
                        hasMore = false,
                        latestBlockHeight = 102,
                        safeBlockHeight = 102,
                    ),
                ),
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                        latestBlockHeight = 110,
                        safeBlockHeight = 110,
                    ),
                ),
            ),
        )

        val firstRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()
        // Replaying from "page-2" would return both events of the final page, now behind the checkpoint.
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", firstRunId))
        assertNull(nullableString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))

        val secondRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", secondRunId))
        val resumed = fakeChainProvider.requestedPageRequests().last()
        assertNull(resumed.cursor)
        assertEquals(102L, resumed.fromBlockHeight)
        assertEquals(4, resumed.fromEventIndex)
    }

    @Test
    fun `empty final page without cursor or high water is rejected without advancing checkpoint`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-empty-no-progress")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-empty-no-progress",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertTrue(
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId)
                .contains("final page without a durable resume cursor or high-water checkpoint"),
        )
        assertEquals(0L, singleLong("SELECT version FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertNull(nullableString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
    }

    @Test
    fun `has more page without next cursor is rejected without advancing checkpoint`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-has-more-null-cursor")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-has-more-null-cursor",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = emptyList(),
                        nextCursor = null,
                        hasMore = true,
                    ),
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertTrue(
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId)
                .contains("hasMore=true without nextCursor"),
        )
        assertEquals(listOf(null), fakeChainProvider.requestedPageRequests().map { it.cursor })
        assertEquals(0L, singleLong("SELECT version FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertNull(nullableString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertEquals(0, tableCount("observed_transactions"))
    }

    @Test
    fun `has more page with same cursor is rejected without advancing checkpoint again`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-has-more-same-cursor")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-has-more-same-cursor",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(
                            providerEvent(
                                txHash = "0xsync-has-more-same-cursor-1",
                                address = "0xsync-has-more-same-cursor",
                                blockHeight = 100,
                            ),
                        ),
                        nextCursor = "page-2",
                        hasMore = true,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = "page-2",
                        events = emptyList(),
                        nextCursor = "page-2",
                        hasMore = true,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertTrue(
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId)
                .contains("hasMore=true without cursor progress"),
        )
        assertEquals(listOf(null, "page-2"), fakeChainProvider.requestedPageRequests().map { it.cursor })
        assertEquals(1L, singleLong("SELECT version FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertEquals(
            "page-2",
            singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)),
        )
        assertEquals(1, tableCount("observed_transactions"))
    }

    @Test
    fun `retry after page two provider failure resumes from last checkpointed cursor`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-page-two-retry")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-page-two-retry",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(providerEvent(txHash = "0xsync-page-two-retry-1", address = "0xsync-page-two-retry", blockHeight = 100)),
                        nextCursor = "page-2",
                        hasMore = true,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
                FakeChainProviderStep.Failure("Provider failed on page two"),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT continuation_count FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(
            "page-2",
            singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)),
        )
        assertEquals(1, tableCount("observed_transactions"))

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-page-two-retry",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = "page-2",
                        events = listOf(providerEvent(txHash = "0xsync-page-two-retry-2", address = "0xsync-page-two-retry", blockHeight = 101)),
                        nextCursor = "final-page",
                        hasMore = false,
                        latestBlockHeight = 101,
                        safeBlockHeight = 101,
                    ),
                ),
            ),
        )
        jdbcTemplate.update(
            "UPDATE sync_runs SET next_attempt_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            syncRunId,
        )
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(listOf(null, "page-2", "page-2"), fakeChainProvider.requestedPageRequests().map { it.cursor })
    }

    @Test
    fun `healthy page continuation increments continuation count without failure attempts`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-continuation-budget")
        val addressId = watchedAddress["id"].asText()
        val pages = (1..51).map { page ->
            FakeChainProviderStep.Page(
                FakeChainProviderPage(
                    expectedCursor = if (page == 1) null else "page-$page",
                    events = listOf(
                        providerEvent(
                            txHash = "0xsync-continuation-budget-$page",
                            address = "0xsync-continuation-budget",
                            blockHeight = 100L + page,
                        ),
                    ),
                    nextCursor = if (page == 51) "final-page" else "page-${page + 1}",
                    hasMore = page < 51,
                    latestBlockHeight = 100L + page,
                    safeBlockHeight = 100L + page,
                ),
            )
        }
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-continuation-budget",
            asset = "USDC",
            steps = pages,
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT continuation_count FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals("CONTINUATION", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(50, tableCount("observed_transactions"))
        assertEquals(
            "page-51",
            singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)),
        )
    }

    @Test
    fun `account sync skips busy early address and processes later address`() {
        val accountId = createAccount()
        val busyAddress = registerAddress(accountId = accountId, address = "0xsync-account-busy")
        val laterAddress = registerAddress(accountId = accountId, address = "0xsync-account-later")
        val busyAddressId = UUID.fromString(busyAddress["id"].asText())
        val laterAddressId = laterAddress["id"].asText()
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

        try {
            fakeChainProvider.setEvents(
                chainId = "local-evm",
                address = "0xsync-account-busy",
                asset = "USDC",
                events = listOf(providerEvent(txHash = "0xsync-account-busy", address = "0xsync-account-busy")),
            )
            fakeChainProvider.setEvents(
                chainId = "local-evm",
                address = "0xsync-account-later",
                asset = "USDC",
                events = listOf(providerEvent(txHash = "0xsync-account-later", address = "0xsync-account-later")),
            )

            val syncRunId = submitAccountSync(accountId)
            runNextClaimedSyncs()

            assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
            assertEquals("LEASE_BUSY", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", syncRunId))
            assertEquals(1, singleInt("SELECT continuation_count FROM sync_runs WHERE id = ?", syncRunId))
            assertEquals(0, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", syncRunId))
            assertEquals(1, tableCount("observed_transactions"))
            assertEquals(
                listOf(FakeChainProviderKey("local-evm", "0xsync-account-later", "USDC")),
                fakeChainProvider.requestedKeys(),
            )
            assertEquals(
                "1",
                singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(laterAddressId)),
            )
        } finally {
            syncCursorRepository.releaseCursorLeaseFenced(
                watchedAddressId = busyAddressId,
                lockedBy = "external-worker",
                lockToken = busyToken,
                updatedAt = Instant.now(),
            )
        }
    }

    @Test
    fun `account sync worker succeeds over multiple active watched addresses`() {
        val accountId = createAccount()
        registerAddress(accountId = accountId, address = "0xsync-account-one", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xsync-account-two", asset = "USDC")

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-account-one",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-account-one", address = "0xsync-account-one")),
        )
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-account-two",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-account-two", address = "0xsync-account-two")),
        )

        val syncRunId = submitAccountSync(accountId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
        assertEquals(
            setOf(
                FakeChainProviderKey("local-evm", "0xsync-account-one", "USDC"),
                FakeChainProviderKey("local-evm", "0xsync-account-two", "USDC"),
            ),
            fakeChainProvider.requestedKeys().toSet(),
        )
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `duplicate in-flight target returns the same sync run before queue cap checks`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-duplicate-target")
        val addressId = watchedAddress["id"].asText()

        val firstRunId = submitAddressSync(addressId)
        val secondRunId = submitAddressSync(addressId)

        assertEquals(firstRunId, secondRunId)
        assertEquals(1, tableCount("sync_runs"))
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", firstRunId))
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
    }

    @Test
    fun `sync uses existing observed event ingestion idempotency`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-duplicate")
        val addressId = watchedAddress["id"].asText()
        val duplicateEvent = providerEvent(txHash = "0xsync-duplicate-tx", address = "0xsync-duplicate")

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-duplicate",
            asset = "USDC",
            events = listOf(duplicateEvent, duplicateEvent),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
    }

    @Test
    fun `retryable provider failure requeues the run without bubbling to POST`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-provider-failure")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-provider-failure",
            asset = "USDC",
            steps = listOf(FakeChainProviderStep.Failure("Provider timeout")),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT attempts FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals("Provider timeout", singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId))
        assertNull(nullableTimestamp("SELECT locked_until FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `retry progress is cumulative across worker attempts`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-retry-progress")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-retry-progress",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Event(
                    providerEvent(txHash = "0xsync-retry-progress-1", address = "0xsync-retry-progress"),
                ),
                FakeChainProviderStep.Failure("Provider failed after partial ingest"),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-retry-progress",
            asset = "USDC",
            events = emptyList(),
        )
        jdbcTemplate.update(
            "UPDATE sync_runs SET next_attempt_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            syncRunId,
        )

        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
    }

    @Test
    fun `partial page ingest failure leaves checkpoint unchanged and retry reprocesses idempotently`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-partial-page-retry")
        val addressId = watchedAddress["id"].asText()
        val firstEvent = providerEvent(
            txHash = "0xsync-partial-page-retry-1",
            address = "0xsync-partial-page-retry",
            blockHeight = 100,
            eventIndex = 0,
        )

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-partial-page-retry",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(
                            firstEvent,
                            providerEvent(
                                txHash = "x".repeat(MAX_TX_HASH_LENGTH + 1),
                                address = "0xsync-partial-page-retry",
                                blockHeight = 100,
                                eventIndex = 1,
                            ),
                        ),
                        nextCursor = "final-page",
                        hasMore = false,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
            ),
        )

        val failedRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", failedRunId))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(0L, singleLong("SELECT version FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertNull(nullableString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-partial-page-retry",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Page(
                    FakeChainProviderPage(
                        expectedCursor = null,
                        events = listOf(
                            firstEvent,
                            providerEvent(
                                txHash = "0xsync-partial-page-retry-2",
                                address = "0xsync-partial-page-retry",
                                blockHeight = 100,
                                eventIndex = 1,
                            ),
                        ),
                        nextCursor = "final-page",
                        hasMore = false,
                        latestBlockHeight = 100,
                        safeBlockHeight = 100,
                    ),
                ),
            ),
        )

        val retryRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", retryRunId))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
        assertEquals("final-page", singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        assertEquals(listOf(null, null), fakeChainProvider.requestedPageRequests().map { it.cursor })
    }

    @Test
    fun `provider data mismatch is terminal failed`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-provider-mismatch")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-provider-mismatch",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-provider-mismatch", address = "0xnot-watched")),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `a page with an invalid event fails terminally before any of its events is written`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-invalid-page")
        val addressId = watchedAddress["id"].asText()
        val validEvent = providerEvent(txHash = "0xsync-invalid-page-1", address = "0xsync-invalid-page", eventIndex = 0)
        val invalidEvents = listOf(
            // PostgreSQL would have rounded the 19th fraction digit away and confirmed the result.
            providerEvent(txHash = "0xsync-invalid-page-2", address = "0xsync-invalid-page", eventIndex = 1, amount = "0.0000000000000000001")
                to "does not fit numeric(38,18)",
            providerEvent(txHash = "0xsync-invalid-page-2", address = "0xsync-invalid-page", eventIndex = 1, amount = "100000000000000000000")
                to "does not fit numeric(38,18)",
            // Domain invariants used to surface as IllegalArgumentException and burn five retries.
            providerEvent(txHash = "0xsync-invalid-page-2", address = "0xsync-invalid-page", eventIndex = 1, amount = "-1")
                to "negative",
            providerEvent(txHash = " ", address = "0xsync-invalid-page", eventIndex = 1)
                to "txHash must not be blank",
            providerEvent(txHash = "0xsync:invalid-page", address = "0xsync-invalid-page", eventIndex = 1)
                to "txHash must not contain whitespace, '/', or ':' on local-evm",
        )

        invalidEvents.forEach { (invalidEvent, expectedError) ->
            fakeChainProvider.setScript(
                chainId = "local-evm",
                address = "0xsync-invalid-page",
                asset = "USDC",
                steps = listOf(
                    FakeChainProviderStep.Page(
                        FakeChainProviderPage(
                            expectedCursor = null,
                            events = listOf(validEvent, invalidEvent),
                            nextCursor = "final-page",
                            hasMore = false,
                            latestBlockHeight = 100,
                            safeBlockHeight = 100,
                        ),
                    ),
                ),
            )

            val syncRunId = submitAddressSync(addressId)
            runNextClaimedSyncs()

            assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId), expectedError)
            val lastError = singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId)
            assertTrue(lastError.contains(expectedError), "last_error for $expectedError: $lastError")
            assertEquals(0, tableCount("observed_transactions"))
            assertEquals(0, tableCount("outbox_events"))
            assertNull(nullableString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", UUID.fromString(addressId)))
        }
    }

    @Test
    fun `events of a chain disabled after registration fail the run terminally`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-disabled-chain")
        val addressId = watchedAddress["id"].asText()
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-disabled-chain",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-disabled-chain-1", address = "0xsync-disabled-chain")),
        )

        val syncRunId = submitAddressSync(addressId)
        jdbcTemplate.update("UPDATE chain_configs SET enabled = false WHERE chain_id = 'local-evm'")
        runNextClaimedSyncs()

        // Terminal on the first attempt instead of five retries with backoff that cannot succeed.
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT attempts FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(
            "Chain local-evm is not enabled, so its events cannot be ingested.",
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId),
        )
        assertEquals(0, tableCount("observed_transactions"))
    }

    @Test
    fun `provider timeout is an absolute deadline and requeues partial attempt`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-slow-trickle")
        val addressId = watchedAddress["id"].asText()
        val steps = (1..20).flatMap { index ->
            listOf(
                FakeChainProviderStep.Delay(Duration.ofMillis(90)),
                FakeChainProviderStep.Event(
                    providerEvent(
                        txHash = "0xsync-slow-trickle-$index",
                        address = "0xsync-slow-trickle",
                        eventIndex = index,
                    ),
                ),
            )
        }

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-slow-trickle",
            asset = "USDC",
            steps = steps,
        )

        val syncRunId = submitAddressSync(addressId)
        val startedAt = System.nanoTime()
        runNextClaimedSyncs()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(elapsed < Duration.ofSeconds(1), "Expected absolute deadline near 100ms, elapsed=$elapsed")
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertTrue(
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId).startsWith("Provider timeout"),
        )
        assertTrue(singleInt("SELECT count(*) FROM observed_transactions") < 20)
    }

    @Test
    fun `provider event database constraint violation is terminal failed`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-db-constraint")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-db-constraint",
            asset = "USDC",
            events = listOf(
                providerEvent(
                    txHash = "x".repeat(MAX_TX_HASH_LENGTH + 1),
                    address = "0xsync-db-constraint",
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        // The constraint violation's message quotes SQL; readers of the run see only its class.
        assertEquals(
            "Database error (DataIntegrityViolationException).",
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId),
        )
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `get sync run succeeds and missing sync run returns not found`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-get")
        val addressId = watchedAddress["id"].asText()

        val syncRunId = submitAddressSync(addressId)

        mockMvc.perform(get("/api/v1/sync-runs/$syncRunId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(syncRunId.toString()))
            .andExpect(jsonPath("$.targetType").value("ADDRESS"))
            .andExpect(jsonPath("$.status").value("QUEUED"))

        mockMvc.perform(get("/api/v1/sync-runs/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Sync run not found"))
    }

    @Test
    fun `address and account sync return not found before creating sync run`() {
        mockMvc.perform(post("/api/v1/addresses/${UUID.randomUUID()}/sync"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Watched address not found"))

        mockMvc.perform(post("/api/v1/accounts/${UUID.randomUUID()}/sync"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Account not found"))

        assertEquals(0, tableCount("sync_runs"))
    }

    @Test
    fun `account sync skips disabled watched addresses`() {
        val accountId = createAccount()
        registerAddress(accountId = accountId, address = "0xsync-active", asset = "USDC")
        val disabledAddress = registerAddress(accountId = accountId, address = "0xsync-disabled", asset = "USDC")
        jdbcTemplate.update(
            "UPDATE watched_addresses SET status = 'DISABLED' WHERE id = ?",
            UUID.fromString(disabledAddress["id"].asText()),
        )

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-active",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-active", address = "0xsync-active")),
        )
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-disabled",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-disabled", address = "0xsync-disabled")),
        )

        val syncRunId = submitAccountSync(accountId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals("0xsync-active", singleString("SELECT address FROM observed_transactions"))
        assertEquals(
            listOf(FakeChainProviderKey("local-evm", "0xsync-active", "USDC")),
            fakeChainProvider.requestedKeys(),
        )
    }

    @Test
    fun `account sync over address cap is terminal failed during worker execution`() {
        val accountId = createAccount()
        registerAddress(accountId = accountId, address = "0xsync-cap-one", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xsync-cap-two", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xsync-cap-three", asset = "USDC")

        val syncRunId = submitAccountSync(accountId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals("Account has more than 2 active watched addresses.", singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
    }

    private fun submitAddressSync(addressId: String): UUID =
        submitSync("/api/v1/addresses/$addressId/sync", expectedTargetId = addressId, expectedTargetType = "ADDRESS")

    private fun submitAccountSync(accountId: String): UUID =
        submitSync("/api/v1/accounts/$accountId/sync", expectedTargetId = accountId, expectedTargetType = "ACCOUNT")

    private fun submitSync(path: String, expectedTargetId: String, expectedTargetType: String): UUID {
        val result = mockMvc.perform(post(path))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.targetType").value(expectedTargetType))
            .andExpect(jsonPath("$.targetId").value(expectedTargetId))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.eventsSeen").value(0))
            .andExpect(jsonPath("$.eventsChanged").value(0))
            .andExpect(jsonPath("$.queuedAt").exists())
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists())
            .andReturn()
        val syncRunId = UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
        assertEquals("/api/v1/sync-runs/$syncRunId", result.response.getHeader("Location"))
        return syncRunId
    }

    private fun runNextClaimedSyncs(limit: Int = 10): List<UUID> {
        val claimed = syncRunLifecycleService.claimDueRuns(
            workerId = "test-worker-${UUID.randomUUID()}",
            limit = limit,
        )
        assertTrue(claimed.isNotEmpty(), "Expected at least one due sync run to be claimed.")
        claimed.forEach { syncApplicationService.executeClaimedSyncRun(it) }
        return claimed.map { it.run.id }
    }

    private fun createAccount(): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"account-${UUID.randomUUID()}"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun registerAddress(
        accountId: String,
        address: String,
        asset: String = "USDC",
    ): JsonNode {
        val result = mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "chainId" to "local-evm",
                            "address" to address,
                            "asset" to asset,
                            "label" to "primary",
                        ),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun providerEvent(
        txHash: String,
        address: String,
        eventIndex: Int = 0,
        asset: String = "USDC",
        amount: String = "12.340000000000000000",
        blockHeight: Long = 100,
        confirmations: Int = 1,
        direction: Direction = Direction.INBOUND,
        status: TransactionStatus = TransactionStatus.SEEN,
    ): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            amount = BigDecimal(amount),
            blockHeight = blockHeight,
            confirmations = confirmations,
            direction = direction,
            status = status,
        )

    private fun assertProviderCallsOutsideTransactions() {
        assertTrue(fakeChainProvider.transactionActiveSnapshots().isNotEmpty())
        assertEquals(setOf(false), fakeChainProvider.transactionActiveSnapshots().toSet())
    }

    private fun tableCount(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun singleLong(sql: String, vararg args: Any): Long =
        requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *args))

    private fun nullableString(sql: String, vararg args: Any): String? =
        jdbcTemplate.queryForObject(sql, String::class.java, *args)

    private fun nullableLong(sql: String, vararg args: Any): Long? =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)

    private fun nullableTimestamp(sql: String, vararg args: Any): Timestamp? =
        jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args)

    private fun cleanDatabase() {
        fakeChainProvider.clear()
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id NOT IN ('local-evm', 'eth-sepolia', 'eth-mainnet')")
        jdbcTemplate.update(
            """
            UPDATE chain_configs
            SET enabled = true,
                required_confirmations = 3,
                updated_at = ?
            WHERE chain_id = 'local-evm'
            """.trimIndent(),
            Timestamp.from(Instant.now()),
        )
    }
}
