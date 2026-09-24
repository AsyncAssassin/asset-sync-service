package com.example.assetsync.unit

import com.example.assetsync.AlchemyJsonRpcStubServer
import com.example.assetsync.MutableClock
import com.example.assetsync.ScriptedAlchemyChain
import com.example.assetsync.ScriptedAlchemyChain.Transfer
import com.example.assetsync.application.account.AssetConfig
import com.example.assetsync.application.account.AssetConfigRepository
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.sync.AddressConfigurationException
import com.example.assetsync.application.sync.ChainProviderEventsPage
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.AlchemyFinalityMode
import com.example.assetsync.config.AlchemyNetworkProperties
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.AlchemyStartMode
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyJsonRpcClient
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyPreflightReport
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyProviderState
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRequiredChain
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.boot.actuate.health.Status
import org.springframework.web.client.RestClient

/**
 * Contract tests of the Alchemy adapter against a scripted JSON-RPC stub. They pin what the
 * service relies on: whole-block emission sorted by `(blockHeight, eventIndex, txHash)`, a cursor
 * that only ever sits on a block boundary, no block above the finality frontier, the range scan
 * with a one-block `fromBlock == toBlock` fallback for paged windows, `pageKey` followed only in
 * memory, provider violations and budget exhaustion as retryable outages that keep the cursor,
 * and bounded cursor and metadata sizes.
 */
class AlchemyChainProviderTests {

    private val watched = "0xAbC0000000000000000000000000000000000001"
    private val other = "0x2222222222222222222222222222222222222222"
    private val contract = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
    private val usdc = AssetConfig(
        chainId = "eth-sepolia",
        asset = "USDC",
        tokenStandard = "ERC20",
        contractAddress = contract,
        decimals = 6,
        displayName = "USD Coin",
        enabled = true,
    )
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = AssetSyncMetrics(meterRegistry, Mockito.mock(OutboxEventRepository::class.java))
    private val stub = AlchemyJsonRpcStubServer()
    private val chain = ScriptedAlchemyChain(watchedAddress = watched, contractAddress = contract).also { stub.responder = it.responder() }

    @AfterTest
    fun tearDown() {
        stub.close()
    }

    @Test
    fun `range scan emits whole blocks sorted across both streams and returns a block-boundary cursor`() {
        chain.transfers += Transfer(block = 100, logIndex = 5, from = other, to = watched)
        chain.transfers += Transfer(block = 102, logIndex = 1, from = watched, to = other)
        chain.transfers += Transfer(block = 105, logIndex = 9, from = other, to = watched.uppercase())
        chain.transfers += Transfer(block = 105, logIndex = 2, from = watched, to = other)
        chain.transfers += Transfer(block = 160, logIndex = 1, from = other, to = watched)

        val page = provider(properties(maxWindowBlocks = 50)).fetchObservedEventsPage(request(cursor = cursor(100)))

        assertEquals(listOf("eth_blockNumber", "eth_getBlockByNumber", "alchemy_getAssetTransfers", "alchemy_getAssetTransfers"), stub.requests.map { it.method })
        assertEquals(listOf("safe", false).toString(), stub.requests[1].params!!.map { if (it.isBoolean) it.asBoolean() else it.asText() }.toString())
        val calls = chain.transfersCalls
        assertEquals(listOf("in", "out"), calls.map { it.direction })
        calls.forEach { call ->
            assertEquals(100L, call.fromBlock)
            assertEquals(149L, call.toBlock, "the window is bounded by max-window-blocks below the safe frontier")
            assertEquals(listOf(contract), call.contractAddresses)
            assertNull(call.pageKey)
            assertEquals("0x3e8", call.params.get("maxCount").asText())
            assertEquals(listOf("erc20"), call.params.get("category").map { it.asText() })
            assertEquals("asc", call.params.get("order").asText())
            assertTrue(call.params.get("withMetadata").asBoolean())
            assertFalse(call.params.get("excludeZeroValue").asBoolean())
        }
        assertEquals(watched.lowercase(), calls[0].params.get("toAddress").asText())
        assertNull(calls[0].params.get("fromAddress"))
        assertEquals(watched.lowercase(), calls[1].params.get("fromAddress").asText())
        assertNull(calls[1].params.get("toAddress"))

        assertEquals(
            listOf(Triple(100L, 5, Direction.INBOUND), Triple(102L, 1, Direction.OUTBOUND), Triple(105L, 2, Direction.OUTBOUND), Triple(105L, 9, Direction.INBOUND)),
            page.events.map { Triple(it.blockHeight, it.eventIndex, it.direction) },
        )
        page.events.forEach { event ->
            assertEquals("eth-sepolia", event.chainId)
            assertEquals(watched.lowercase(), event.address)
            assertEquals("USDC", event.asset)
            assertEquals(BigDecimal("1.234567000000000000"), event.amount)
            assertEquals((200 - event.blockHeight + 1).toInt(), event.confirmations)
            assertEquals(TransactionStatus.SEEN, event.status)
            assertEquals(event.txHash, event.txHash.lowercase())
        }
        assertEquals(ScriptedAlchemyChain.hashFor(100, 5), page.events[0].txHash)
        assertEquals(cursor(150), page.nextCursor)
        assertTrue(page.hasMore)
        assertEquals(200L, page.latestBlockHeight)
        assertEquals(180L, page.safeBlockHeight)
        assertMetadata(page, mode = "range", blocksFullyDrained = 50, nextBlock = 150, rpcCalls = 4, oneBlockFallbacks = 0)
        val metadata = requireNotNull(page.metadata)
        assertEquals(contract, metadata.get("contractAddress").asText())
        assertEquals(0, metadata.get("skippedSelfTransfers").asInt())
        assertEquals(0, metadata.get("skippedWrongTokenRows").asInt())
        assertTrue(metadata.get("blockComplete").asBoolean())
        assertTrue(page.nextCursor!!.length < 128)
        assertTrue(objectMapper.writeValueAsBytes(page.metadata).size < 1024, "metadata must stay far below the checkpoint limit")
    }

    @Test
    fun `the window never exceeds the safe frontier and finishing at safe reports no more`() {
        chain.transfers += Transfer(block = 190, logIndex = 1, from = other, to = watched)

        val page = provider(properties(maxWindowBlocks = 50)).fetchObservedEventsPage(request(cursor = cursor(170)))

        assertEquals(listOf(180L, 180L), chain.transfersCalls.map { it.toBlock })
        assertEquals(emptyList(), page.events, "block 190 is above the safe frontier")
        assertEquals(cursor(181), page.nextCursor)
        assertFalse(page.hasMore)
        assertMetadata(page, mode = "range", blocksFullyDrained = 11, nextBlock = 181, rpcCalls = 4, oneBlockFallbacks = 0)
    }

    @Test
    fun `registration-safe first fetch is idle at safe plus one and records the initial start block`() {
        chain.transfers += Transfer(block = 150, logIndex = 1, from = other, to = watched)

        val page = provider().fetchObservedEventsPage(request(cursor = null))

        assertEquals(2, stub.requests.size, "an idle page needs no transfers call")
        assertEquals(emptyList(), page.events, "no backfill before registration")
        assertFalse(page.hasMore)
        assertEquals(cursor(181), page.nextCursor)
        val idleMetadata = requireNotNull(page.metadata)
        assertEquals(181L, idleMetadata.get("initialStartBlock").asLong())
        assertFalse(idleMetadata.get("highWaterAdjusted").asBoolean())
        assertMetadata(page, mode = "idle", blocksFullyDrained = 0, nextBlock = 181, rpcCalls = 2, oneBlockFallbacks = 0)

        // The next fetch resumes from the recorded cursor once the frontier moved on.
        stub.requests.clear()
        chain.latest = 260
        chain.finality["safe"] = 240
        chain.transfers += Transfer(block = 200, logIndex = 4, from = other, to = watched)
        val later = provider().fetchObservedEventsPage(request(cursor = page.nextCursor, checkpoint = page.metadata))
        assertEquals(listOf(200L), later.events.map { it.blockHeight })
        assertEquals(181L, later.metadata!!.get("initialStartBlock").asLong(), "the initial start is carried forward")
        assertEquals(cursor(241), later.nextCursor)
    }

    @Test
    fun `configured-block start uses the configured block, and without one the chain is not served`() {
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        val configured = properties(
            startMode = AlchemyStartMode.CONFIGURED_BLOCK,
            networks = mapOf("eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia", startBlock = 100)),
        )

        val page = provider(configured).fetchObservedEventsPage(request(cursor = null))

        assertEquals(100L, chain.transfersCalls.first().fromBlock)
        assertEquals(listOf(100L), page.events.map { it.blockHeight })
        assertEquals(100L, page.metadata!!.get("initialStartBlock").asLong())

        val missingStartBlock = provider(properties(startMode = AlchemyStartMode.CONFIGURED_BLOCK))
        assertTrue(provider(configured).supportsChain("eth-sepolia"))
        assertFalse(missingStartBlock.supportsChain("eth-sepolia"), "registration must refuse a chain without a start block")
        // An address registered before would fail on its own, like one on an unmapped chain.
        val failure = assertThrows<AddressConfigurationException> { missingStartBlock.fetchObservedEventsPage(request(cursor = null)) }
        assertTrue(failure.message!!.contains("networks.eth-sepolia.start-block"), failure.message)
        assertEquals(failure.message, missingStartBlock.lastDataError())
        assertEquals(Status.UP, AlchemyChainProviderHealthIndicator(missingStartBlock).health().status)
    }

    @Test
    fun `a stored high-water at or above the start moves the start to the next block with a diagnostic`() {
        chain.transfers += Transfer(block = 120, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 121, logIndex = 1, from = other, to = watched)
        val checkpoint = objectMapper.createObjectNode().put("initialStartBlock", 90)

        val page = provider(properties(maxWindowBlocks = 50)).fetchObservedEventsPage(
            request(cursor = cursor(100), fromBlockHeight = 120, checkpoint = checkpoint),
        )

        assertEquals(121L, chain.transfersCalls.first().fromBlock)
        assertEquals(listOf(121L), page.events.map { it.blockHeight })
        val metadata = requireNotNull(page.metadata)
        assertTrue(metadata.get("highWaterAdjusted").asBoolean())
        assertEquals(90L, metadata.get("initialStartBlock").asLong())
        assertEquals(cursor(171), page.nextCursor)
    }

    @Test
    fun `limit truncates only at a block boundary and an oversized block is terminal`() {
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 2, from = watched, to = other)
        chain.transfers += Transfer(block = 101, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 101, logIndex = 2, from = other, to = watched)

        val page = provider().fetchObservedEventsPage(request(cursor = cursor(100), limit = 3))

        assertEquals(listOf(100L, 100L), page.events.map { it.blockHeight })
        assertEquals(cursor(101), page.nextCursor)
        assertTrue(page.hasMore)
        assertMetadata(page, mode = "range", blocksFullyDrained = 1, nextBlock = 101, rpcCalls = 4, oneBlockFallbacks = 0)

        chain.transfers.clear()
        (1..4).forEach { index -> chain.transfers += Transfer(block = 100, logIndex = index, from = other, to = watched) }
        val oversizedProvider = provider()
        val oversized = assertThrows<AddressConfigurationException> { oversizedProvider.fetchObservedEventsPage(request(cursor = cursor(100), limit = 3)) }
        assertTrue(oversized.message!!.contains("Alchemy block 100 has 4 ERC20 events"), oversized.message)
        assertTrue(oversized.message!!.contains("exceeding request.limit=3"), oversized.message)
        assertEquals(oversized.message, oversizedProvider.lastDataError())
        assertNull(oversizedProvider.lastError())
        assertEquals(Status.UP, AlchemyChainProviderHealthIndicator(oversizedProvider).health().status)
    }

    @Test
    fun `a paged window falls back to draining one block with pageKey followed in memory`() {
        chain.pageSize = 1
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 3, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 2, from = watched, to = other)

        val page = provider(properties(maxRpcCallsPerFetch = 8, maxWindowBlocks = 50)).fetchObservedEventsPage(request(cursor = cursor(100)))

        val calls = chain.transfersCalls
        assertEquals(6, calls.size, calls.toString())
        assertEquals(Triple(100L, 149L, "in"), calls[0].let { Triple(it.fromBlock, it.toBlock, it.direction) }, "the window scan comes first")
        calls.subList(1, 4).forEach { call ->
            assertEquals(100L, call.fromBlock)
            assertEquals(100L, call.toBlock, "the fallback drains exactly one block")
        }
        assertEquals(listOf("in", "in", "out"), calls.subList(1, 4).map { it.direction })
        assertNull(calls[1].pageKey)
        assertEquals("pk:in:100:100:1", calls[2].pageKey, "the continuation carries the provider pageKey")
        assertEquals(Triple(101L, 149L, "in"), calls[4].let { Triple(it.fromBlock, it.toBlock, it.direction) }, "the rest of the window is scanned as a range")
        assertEquals(Triple(101L, 149L, "out"), calls[5].let { Triple(it.fromBlock, it.toBlock, it.direction) })

        assertEquals(listOf(1, 2, 3), page.events.map { it.eventIndex })
        assertEquals(listOf(Direction.INBOUND, Direction.OUTBOUND, Direction.INBOUND), page.events.map { it.direction })
        assertEquals(cursor(150), page.nextCursor)
        assertTrue(page.hasMore)
        assertMetadata(page, mode = "one-block", blocksFullyDrained = 50, nextBlock = 150, rpcCalls = 8, oneBlockFallbacks = 1)
        assertFalse(page.nextCursor!!.contains("pk:"))
        assertFalse(objectMapper.writeValueAsString(page.metadata).contains("pk:"), "pageKey never reaches durable state")
        assertEquals(1.0, counter("asset.sync.provider.alchemy.block.fallbacks", "network", "eth-sepolia"))
        assertEquals(6.0, counter("asset.sync.provider.alchemy.rpc", "method", "alchemy_getAssetTransfers", "result", "SUCCEEDED"))
        assertEquals(8.0, meterRegistry.find("asset.sync.provider.alchemy.rpc.duration").timers().sumOf { it.count().toDouble() })
    }

    @Test
    fun `a paged window narrows to the blocks before the page boundary and drains only the boundary block alone`() {
        chain.pageSize = 2
        chain.transfers += Transfer(block = 101, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 104, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 104, logIndex = 2, from = other, to = watched)
        chain.transfers += Transfer(block = 104, logIndex = 3, from = other, to = watched)

        val page = provider(properties(maxRpcCallsPerFetch = 20, maxWindowBlocks = 8)).fetchObservedEventsPage(request(cursor = cursor(100)))

        assertEquals(
            listOf(
                Triple(100L, 107L, "in"),
                Triple(100L, 103L, "in"),
                Triple(100L, 103L, "out"),
                Triple(104L, 107L, "in"),
                Triple(104L, 104L, "in"),
                Triple(104L, 104L, "in"),
                Triple(104L, 104L, "out"),
                Triple(105L, 107L, "in"),
                Triple(105L, 107L, "out"),
            ),
            chain.transfersCalls.map { Triple(it.fromBlock, it.toBlock, it.direction) },
            "the paged window is re-queried up to block 103, then block 104 is drained alone through its pageKey, then the rest is one range",
        )
        assertEquals("pk:in:104:104:2", chain.transfersCalls[5].pageKey, "the continuation resumes after the two rows of page one")
        assertEquals(listOf(101L to 1, 104L to 1, 104L to 2, 104L to 3), page.events.map { it.blockHeight to it.eventIndex })
        assertEquals(cursor(108), page.nextCursor)
        assertTrue(page.hasMore)
        assertMetadata(page, mode = "one-block", blocksFullyDrained = 8, nextBlock = 108, rpcCalls = 11, oneBlockFallbacks = 1)
        assertEquals(1, page.metadata!!.get("scan").get("narrowings").asInt())
        assertEquals(1.0, counter("asset.sync.provider.alchemy.narrowings", "network", "eth-sepolia"))
    }

    @Test
    fun `narrowing is skipped when the budget could not also drain the boundary block`() {
        chain.pageSize = 2
        chain.transfers += Transfer(block = 101, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 104, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 104, logIndex = 2, from = other, to = watched)
        chain.transfers += Transfer(block = 104, logIndex = 3, from = other, to = watched)

        // Six calls: two head calls, one paged attempt, then three remain, below the narrowing reserve.
        val page = provider(properties(maxRpcCallsPerFetch = 6, maxWindowBlocks = 8)).fetchObservedEventsPage(request(cursor = cursor(100)))

        assertEquals(
            listOf(Triple(100L, 107L, "in"), Triple(100L, 100L, "in"), Triple(100L, 100L, "out")),
            chain.transfersCalls.map { Triple(it.fromBlock, it.toBlock, it.direction) },
        )
        assertEquals(emptyList(), page.events)
        assertEquals(cursor(101), page.nextCursor)
        assertEquals(0, page.metadata!!.get("scan").get("narrowings").asInt())
        assertEquals(0.0, counter("asset.sync.provider.alchemy.narrowings", "network", "eth-sepolia"))
    }

    @Test
    fun `continuation pages with lower log indexes are sorted in rather than skipped`() {
        chain.pageSize = 1
        chain.descendingPages = true
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 3, from = other, to = watched)

        val page = provider(properties(maxRpcCallsPerFetch = 8)).fetchObservedEventsPage(request(cursor = cursor(100)))

        assertEquals(listOf(1, 3), page.events.map { it.eventIndex })
        assertEquals(cursor(181), page.nextCursor)
    }

    @Test
    fun `a repeated pageKey, a restarted page, and an out-of-window row are retryable and keep the cursor`() {
        chain.pageSize = 1
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 3, from = other, to = watched)

        chain.pageKeyMode = ScriptedAlchemyChain.PageKeyMode.REPEAT_KEY
        val repeated = assertThrows<ChainProviderUnavailableException> { provider(properties(maxRpcCallsPerFetch = 10)).fetchObservedEventsPage(request(cursor = cursor(100))) }
        assertTrue(repeated.message!!.contains("repeated a pageKey while draining block 100"), repeated.message)

        chain.pageKeyMode = ScriptedAlchemyChain.PageKeyMode.RESTART_PAGE
        val restarted = assertThrows<ChainProviderUnavailableException> { provider(properties(maxRpcCallsPerFetch = 10)).fetchObservedEventsPage(request(cursor = cursor(100))) }
        assertTrue(restarted.message!!.contains("repeated transfer"), restarted.message)
        assertTrue(restarted.message!!.contains("across pageKey pages of block 100"), restarted.message)

        chain.pageKeyMode = ScriptedAlchemyChain.PageKeyMode.NORMAL
        chain.outOfWindowRow = Transfer(block = 101, logIndex = 1, from = other, to = watched)
        val outOfWindow = assertThrows<ChainProviderUnavailableException> { provider(properties(maxRpcCallsPerFetch = 10)).fetchObservedEventsPage(request(cursor = cursor(100))) }
        assertTrue(outOfWindow.message!!.contains("returned block 101 for a 100..100 request"), outOfWindow.message)
    }

    @Test
    fun `budget exhaustion before the first drained block is retryable and after it returns the prefix`() {
        chain.pageSize = 1
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 3, from = other, to = watched)

        val exhausted = assertThrows<ChainProviderUnavailableException> { provider(properties(maxRpcCallsPerFetch = 4)).fetchObservedEventsPage(request(cursor = cursor(100))) }
        assertTrue(exhausted.message!!.contains("budget exhausted before block 100 was fully drained"), exhausted.message)
        assertTrue(exhausted.message!!.contains("3 of 4 RPC calls"), "one call is left after the paged attempt, too few to drain a block: ${exhausted.message}")

        chain.transfers.clear()
        chain.transfersCalls.clear()
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 101, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 101, logIndex = 2, from = other, to = watched)

        val prefix = provider(properties(maxRpcCallsPerFetch = 6)).fetchObservedEventsPage(request(cursor = cursor(100)))

        assertEquals(listOf(100L), prefix.events.map { it.blockHeight })
        assertEquals(cursor(101), prefix.nextCursor, "block 101 stays for the next fetch")
        assertTrue(prefix.hasMore)
        assertMetadata(prefix, mode = "one-block", blocksFullyDrained = 1, nextBlock = 101, rpcCalls = 5, oneBlockFallbacks = 1)
    }

    @Test
    fun `self-transfers and wrong-token rows are skipped, counted, and still advance the cursor`() {
        chain.applyContractFilter = false
        chain.transfers += Transfer(block = 100, logIndex = 1, from = watched, to = watched)
        chain.transfers += Transfer(block = 101, logIndex = 2, from = other, to = watched, contract = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val page = provider(properties(maxWindowBlocks = 50)).fetchObservedEventsPage(request(cursor = cursor(100)))

        assertEquals(emptyList(), page.events)
        assertEquals(cursor(150), page.nextCursor)
        assertTrue(page.hasMore)
        val metadata = requireNotNull(page.metadata)
        assertEquals(1, metadata.get("skippedSelfTransfers").asInt())
        assertEquals(1, metadata.get("skippedWrongTokenRows").asInt())
        assertEquals(0, metadata.get("skippedBelowHighWater").asInt())
        assertEquals(1.0, counter("asset.sync.provider.alchemy.skipped.rows", "reason", "SELF_TRANSFER"))
        assertEquals(1.0, counter("asset.sync.provider.alchemy.skipped.rows", "reason", "WRONG_TOKEN"))
        assertEquals(0.0, counter("asset.sync.provider.alchemy.skipped.rows", "reason", "BELOW_HIGH_WATER"))
    }

    @Test
    fun `finality modes resolve the frontier from the tag, the depth, or the fallback`() {
        val safe = provider(properties(finalityMode = AlchemyFinalityMode.SAFE)).fetchObservedEventsPage(request(cursor = cursor(100)))
        assertEquals("safe", stub.requests[1].params!!.get(0).asText())
        assertEquals(180L, safe.safeBlockHeight)
        val safeMetadata = requireNotNull(safe.metadata)
        assertFalse(safeMetadata.get("finalityFallback").asBoolean())
        assertEquals("safe", safeMetadata.get("finalityMode").asText())

        stub.requests.clear()
        val finalized = provider(properties(finalityMode = AlchemyFinalityMode.FINALIZED)).fetchObservedEventsPage(request(cursor = cursor(100)))
        assertEquals("finalized", stub.requests[1].params!!.get(0).asText())
        assertEquals(170L, finalized.safeBlockHeight)

        stub.requests.clear()
        val depth = provider(properties(finalityMode = AlchemyFinalityMode.DEPTH, finalityDepthFallback = 64)).fetchObservedEventsPage(request(cursor = cursor(100)))
        assertEquals(listOf("eth_blockNumber", "alchemy_getAssetTransfers", "alchemy_getAssetTransfers"), stub.requests.map { it.method })
        assertEquals(136L, depth.safeBlockHeight)

        stub.requests.clear()
        chain.finality["safe"] = null
        val nullBlock = provider(properties(finalityMode = AlchemyFinalityMode.SAFE, finalityDepthFallback = 64)).fetchObservedEventsPage(request(cursor = cursor(100)))
        assertEquals(136L, nullBlock.safeBlockHeight)
        assertTrue(nullBlock.metadata!!.get("finalityFallback").asBoolean())

        stub.requests.clear()
        chain.finality.remove("safe")
        chain.unknownTagIsError = true
        val unsupported = provider(properties(finalityMode = AlchemyFinalityMode.SAFE, finalityDepthFallback = 64)).fetchObservedEventsPage(request(cursor = cursor(100)))
        assertEquals(136L, unsupported.safeBlockHeight)
        assertTrue(unsupported.metadata!!.get("finalityFallback").asBoolean())
    }

    @Test
    fun `a safe frontier above latest is retryable`() {
        chain.finality["safe"] = 250

        val exception = assertThrows<ChainProviderUnavailableException> { provider().fetchObservedEventsPage(request(cursor = cursor(100))) }

        assertTrue(exception.message!!.contains("safe block 250 is above the latest block 200"), exception.message)
    }

    @Test
    fun `a configuration gap of one address is a data error that keeps the provider up`() {
        listOf(
            provider() to request(cursor = cursor(100), chainId = "local-evm"),
            provider(assetConfig = null) to request(cursor = cursor(100)),
        ).forEach { (gapped, gappedRequest) ->
            val failure = assertThrows<AddressConfigurationException> { gapped.fetchObservedEventsPage(gappedRequest) }

            assertFalse(failure.message!!.contains("disable the chain"), "a disabled chain still syncs its active addresses: ${failure.message}")
            assertEquals(AlchemyProviderState.PROBE_SUCCEEDED, gapped.state())
            assertEquals(failure.message, gapped.lastDataError())
            assertNull(gapped.lastError())
            assertEquals(Status.UP, AlchemyChainProviderHealthIndicator(gapped).health().status)
        }
        assertEquals(emptyList(), stub.requests, "a configuration gap must not spend RPC calls")
    }

    @Test
    fun `only chains mapped to an alchemy network are served`() {
        assertTrue(provider().supportsChain("eth-sepolia"))
        assertFalse(provider().supportsChain("local-evm"))
    }

    @Test
    fun `invalid input fails before any rpc call`() {
        assertThrows<ProviderDataInvalidException> { provider().fetchObservedEventsPage(request(cursor = "not-a-cursor")) }
        assertThrows<ProviderConfigurationException> { provider().fetchObservedEventsPage(request(cursor = cursor(100), chainId = "local-evm")) }
        assertThrows<ProviderConfigurationException> { provider(assetConfig = null).fetchObservedEventsPage(request(cursor = cursor(100))) }
        assertThrows<ProviderDataInvalidException> {
            provider(assetConfig = usdc.copy(tokenStandard = "ERC721")).fetchObservedEventsPage(request(cursor = cursor(100)))
        }
        assertThrows<ProviderDataInvalidException> { provider().fetchObservedEventsPage(request(cursor = cursor(100), limit = 0)) }

        assertEquals(emptyList(), stub.requests, "validation must not spend RPC calls")
    }

    @Test
    fun `the hard deadline stops calls and is retryable when nothing was drained`() {
        val clock = MutableClock()
        chain.pageSize = 1
        chain.transfers += Transfer(block = 100, logIndex = 1, from = other, to = watched)
        chain.transfers += Transfer(block = 100, logIndex = 3, from = other, to = watched)
        chain.beforeTransfersResponse = { clock.advance(Duration.ofSeconds(6)) }

        val exception = assertThrows<ChainProviderUnavailableException> {
            provider(properties(maxRpcCallsPerFetch = 20), clock = clock, providerTimeout = Duration.ofSeconds(10))
                .fetchObservedEventsPage(request(cursor = cursor(100)))
        }

        assertTrue(exception.message!!.contains("budget exhausted before block 100 was fully drained"), exception.message)
        assertEquals(4, stub.requests.size, "no call starts after the hard deadline")
    }

    private fun assertMetadata(page: ChainProviderEventsPage, mode: String, blocksFullyDrained: Long, nextBlock: Long, rpcCalls: Int, oneBlockFallbacks: Int) {
        val metadata = requireNotNull(page.metadata)
        assertEquals("alchemy", metadata.get("provider").asText())
        assertEquals(1, metadata.get("cursorVersion").asInt())
        assertEquals("eth-sepolia", metadata.get("chainId").asText())
        assertEquals("eth-sepolia", metadata.get("network").asText())
        assertEquals("USDC", metadata.get("asset").asText())
        val scan = metadata.get("scan")
        assertEquals(mode, scan.get("mode").asText())
        assertEquals(blocksFullyDrained, scan.get("blocksFullyDrained").asLong())
        assertEquals(nextBlock - 1, scan.get("lastScannedBlock").asLong())
        assertEquals(rpcCalls, scan.get("rpcCalls").asInt())
        assertEquals(oneBlockFallbacks, scan.get("oneBlockFallbacks").asInt())
        assertEquals(nextBlock, metadata.get("nextBlock").asLong())
        assertEquals(page.latestBlockHeight, metadata.get("latestBlockHeight").asLong())
        assertEquals(page.safeBlockHeight, metadata.get("safeBlockHeight").asLong())
    }

    private fun counter(name: String, vararg tags: String): Double =
        meterRegistry.find(name).tags(*tags).counter()?.count() ?: 0.0

    private fun cursor(nextBlock: Long): String = """{"v":1,"p":"alchemy","nextBlock":$nextBlock}"""

    private fun properties(
        startMode: AlchemyStartMode = AlchemyStartMode.REGISTRATION_SAFE,
        networks: Map<String, AlchemyNetworkProperties> = mapOf("eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia")),
        finalityMode: AlchemyFinalityMode = AlchemyFinalityMode.SAFE,
        finalityDepthFallback: Long = 64,
        maxWindowBlocks: Int = 5000,
        maxRpcCallsPerFetch: Int = 6,
    ): AlchemyProviderProperties =
        AlchemyProviderProperties(
            apiKey = "test-key",
            endpointTemplate = stub.headerEndpointTemplate(),
            startMode = startMode,
            networks = networks,
            finalityMode = finalityMode,
            finalityDepthFallback = finalityDepthFallback,
            maxWindowBlocks = maxWindowBlocks,
            maxRpcCallsPerFetch = maxRpcCallsPerFetch,
        )

    private fun provider(
        properties: AlchemyProviderProperties = properties(),
        assetConfig: AssetConfig? = usdc,
        clock: Clock = Clock.systemUTC(),
        providerTimeout: Duration = Duration.ofSeconds(10),
    ): AlchemyChainProvider =
        AlchemyChainProvider(
            properties = properties,
            preflight = AlchemyPreflightReport(
                requiredChains = listOf(AlchemyRequiredChain("eth-sepolia", setOf("ERC20"))),
                probedBlockHeights = mapOf("eth-sepolia" to 1L),
            ),
            client = AlchemyJsonRpcClient(restClient = RestClient.builder().build(), properties = properties, objectMapper = objectMapper, metrics = metrics),
            assetConfigRepository = object : AssetConfigRepository {
                override fun findEnabledByChainIdAndAsset(chainId: String, asset: String): AssetConfig? =
                    assetConfig?.takeIf { it.chainId == chainId && it.asset == asset }
            },
            objectMapper = objectMapper,
            providerTimeout = providerTimeout,
            clock = clock,
            metrics = metrics,
        )

    private fun request(
        cursor: String?,
        chainId: String = "eth-sepolia",
        limit: Int = 100,
        fromBlockHeight: Long? = null,
        checkpoint: com.fasterxml.jackson.databind.JsonNode? = null,
    ): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = chainId,
            address = watched,
            asset = "usdc",
            cursor = cursor,
            limit = limit,
            fromBlockHeight = fromBlockHeight,
            safeBlockHeight = null,
            checkpoint = checkpoint,
        )
}
