package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.account.AssetConfigRepository
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.sync.ChainProviderEventsPage
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.AlchemyAuthMode
import com.example.assetsync.config.AlchemyFinalityMode
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.AlchemyStartMode
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import com.example.assetsync.domain.policy.NormalizedChainIdentity
import com.example.assetsync.application.account.AssetConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Alchemy-backed [ChainProviderPort] for ERC-20 transfers of one watched address and one
 * registry asset.
 *
 * One fetch: `eth_blockNumber`, the finality frontier (`safe`/`finalized` tag or latest minus a
 * depth), then a scan that starts at the durable cursor's `nextBlock` and never looks above the
 * frontier. The scan asks `alchemy_getAssetTransfers` for the whole window (at most
 * `max-window-blocks`) once per direction; a window answered without `pageKey` is complete for
 * every block in it, so empty stretches cost two calls instead of two calls per block. A window
 * that comes back paged is not trusted across pages, but its first page still shows the last block
 * it reached, and ascending order means every block before that boundary was covered in full: the
 * adapter narrows the range to those blocks and re-queries them while the RPC budget allows, then
 * drains the boundary block alone (`fromBlock == toBlock`), following `pageKey` in memory, and
 * continues with the rest of the window. Durable progress moves only past fully drained blocks: the returned cursor is
 * always a block boundary, events are emitted for whole blocks only, and a block that cannot be
 * finished inside the RPC and time budget is left for the next fetch or, if nothing was finished,
 * reported as a retryable outage that keeps the checkpoint.
 */
class AlchemyChainProvider(
    private val properties: AlchemyProviderProperties,
    preflight: AlchemyPreflightReport,
    private val client: AlchemyJsonRpcClient,
    private val assetConfigRepository: AssetConfigRepository,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val providerTimeout: Duration = Duration.ofSeconds(10),
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: AssetSyncMetrics? = null,
) : ChainProviderPort {

    private val logger = LoggerFactory.getLogger(AlchemyChainProvider::class.java)
    private val scrubber = AlchemySecretScrubber(properties.apiKey)
    private val finalityFallbackWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Alchemy networks proven reachable by the startup probe, never endpoints or credentials. */
    val networks: List<String> = preflight.probedNetworks

    val authMode: AlchemyAuthMode
        get() = properties.authMode

    @Volatile
    private var state: AlchemyProviderState =
        if (networks.isEmpty()) AlchemyProviderState.NO_REQUIRED_NETWORKS else AlchemyProviderState.PROBE_SUCCEEDED

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastDataError: String? = null

    override fun fetchObservedEventsPage(request: ChainProviderEventsPageRequest): ChainProviderEventsPage =
        try {
            val page = Fetch(request).run()
            state = AlchemyProviderState.FETCH_SUCCEEDED
            lastError = null
            lastDataError = null
            page
        } catch (exception: ProviderDataInvalidException) {
            // Invalid data for one address (a rejected parameter, an unmappable row) says nothing
            // about Alchemy's availability, so the state stays and health keeps it as a detail.
            lastDataError = scrubber.scrub(exception.message).take(MAX_ERROR_LENGTH)
            logFailure(request, lastDataError)
            throw exception
        } catch (exception: RuntimeException) {
            recordFailure(request, exception)
            throw exception
        }

    fun state(): AlchemyProviderState = state

    fun lastError(): String? = lastError

    fun lastDataError(): String? = lastDataError

    private fun recordFailure(request: ChainProviderEventsPageRequest, exception: RuntimeException) {
        val error = scrubber.scrub(exception.message).take(MAX_ERROR_LENGTH)
        state = AlchemyProviderState.FETCH_FAILED
        lastError = error
        logFailure(request, error)
    }

    private fun logFailure(request: ChainProviderEventsPageRequest, error: String?) {
        logger.warn(
            "alchemy_provider_page_fetch_failed chainId={} address={} asset={} limit={} error={}",
            request.chainId,
            request.address,
            request.asset,
            request.limit,
            error,
        )
    }

    private data class Finality(val safeBlockHeight: Long, val fallback: Boolean)

    private data class StreamPage(val outcomes: List<AlchemyTransferMapper.Outcome>, val pageKey: String?)

    private sealed interface ScanResult {
        /** Every block of the scanned range is drained; `events` are sorted and cover all of them. */
        data class Complete(val events: List<ChainProviderObservedEvent>) : ScanResult

        /** The range came back paged; `boundaryBlock` is the last block its first page reached. */
        data class Paged(val boundaryBlock: Long) : ScanResult

        /** The RPC or time budget ran out before the range was drained. */
        data object Exhausted : ScanResult
    }

    private class SkipCounters {
        val selfTransfers = LinkedHashSet<String>()
        val wrongTokenRows = LinkedHashSet<String>()
        var belowHighWater = 0
    }

    /** Request-scoped state of one page fetch. */
    private inner class Fetch(private val request: ChainProviderEventsPageRequest) {

        private val startedAt: Instant = clock.instant()
        private val budget = AlchemyFetchBudget(
            maxRpcCalls = properties.maxRpcCallsPerFetch,
            softDeadline = startedAt.plus(Duration.ofMillis((providerTimeout.toMillis() * SOFT_DEADLINE_FRACTION).toLong())),
            hardDeadline = startedAt.plus(providerTimeout),
            clock = clock,
        )
        private val skips = SkipCounters()
        private lateinit var network: String
        private lateinit var identity: NormalizedChainIdentity
        private lateinit var assetConfig: AssetConfig
        private lateinit var mapper: AlchemyTransferMapper
        private var latestBlockHeight: Long = 0
        private var oneBlockFallbacks = 0
        private var narrowings = 0

        fun run(): ChainProviderEventsPage {
            network = properties.networkFor(request.chainId)?.network
                ?: throw ProviderConfigurationException(
                    "No Alchemy network is mapped for chain ${request.chainId}; add " +
                        "${AlchemyProviderProperties.PREFIX}.networks.${request.chainId}.network or disable the chain.",
                )
            identity = ChainIdentityNormalizer.normalize(chainId = request.chainId, address = request.address, asset = request.asset)
            assetConfig = assetConfigRepository.findEnabledByChainIdAndAsset(identity.chainId, identity.asset)
                ?: throw ProviderConfigurationException(
                    "No enabled asset config for (${identity.chainId}, ${identity.asset}); the registry preflight should have caught this.",
                )
            if (assetConfig.tokenStandard != AlchemyRolloutRules.SUPPORTED_TOKEN_STANDARD) {
                throw ProviderDataInvalidException(
                    "Alchemy adapter supports ${AlchemyRolloutRules.SUPPORTED_TOKEN_STANDARD} only; " +
                        "(${identity.chainId}, ${identity.asset}) has token standard ${assetConfig.tokenStandard}.",
                )
            }
            if (request.limit <= 0) {
                throw ProviderDataInvalidException("Alchemy adapter received a non-positive page limit.")
            }
            val cursor = AlchemyCursor.decode(request.cursor, objectMapper)

            budget.consumeOrThrow("the latest block call")
            latestBlockHeight = client.blockNumber(network, budget.hardDeadline)
            val finality = resolveFinality()
            val safeBlockHeight = finality.safeBlockHeight
            if (safeBlockHeight > latestBlockHeight) {
                throw ChainProviderUnavailableException(
                    "Alchemy safe block $safeBlockHeight is above the latest block $latestBlockHeight for network $network; retrying.",
                )
            }
            mapper = AlchemyTransferMapper(identity = identity, assetConfig = assetConfig, latestBlockHeight = latestBlockHeight)

            val start = resolveStart(cursor = cursor, safeBlockHeight = safeBlockHeight)
            if (start.block > safeBlockHeight) {
                return page(
                    events = emptyList(),
                    nextBlock = start.block,
                    safeBlockHeight = safeBlockHeight,
                    finality = finality,
                    start = start,
                    mode = "idle",
                    blocksFullyDrained = 0,
                )
            }

            val scan = scanWindow(start = start.block, safeBlockHeight = safeBlockHeight)
            return page(
                events = scan.events,
                nextBlock = scan.nextBlock,
                safeBlockHeight = safeBlockHeight,
                finality = finality,
                start = start,
                mode = if (oneBlockFallbacks > 0) "one-block" else "range",
                blocksFullyDrained = scan.nextBlock - start.block,
            )
        }

        private fun resolveFinality(): Finality =
            when (properties.finalityMode) {
                AlchemyFinalityMode.DEPTH -> Finality(safeBlockHeight = depthFrontier(), fallback = false)
                AlchemyFinalityMode.SAFE, AlchemyFinalityMode.FINALIZED -> {
                    val tag = properties.finalityMode.name.lowercase()
                    budget.consumeOrThrow("the $tag block call")
                    val tagged = try {
                        client.blockNumberByTag(network = network, tag = tag, deadline = budget.hardDeadline)
                    } catch (exception: ProviderDataInvalidException) {
                        warnFinalityFallback(tag, exception.message)
                        null
                    }
                    if (tagged == null) {
                        warnFinalityFallback(tag, "null block")
                        Finality(safeBlockHeight = depthFrontier(), fallback = true)
                    } else {
                        Finality(safeBlockHeight = tagged, fallback = false)
                    }
                }
            }

        private fun depthFrontier(): Long = maxOf(0L, latestBlockHeight - properties.finalityDepthFallback)

        private fun warnFinalityFallback(tag: String, reason: String?) {
            if (finalityFallbackWarned.add("$network:$tag")) {
                logger.warn(
                    "alchemy_finality_tag_unavailable network={} tag={} fallbackDepth={} reason={}",
                    network,
                    tag,
                    properties.finalityDepthFallback,
                    scrubber.scrub(reason),
                )
            }
        }

        private inner class Start(val block: Long, val initialStartBlock: Long?, val highWaterAdjusted: Boolean)

        /**
         * Without a cursor the start comes from the start mode; a stored event high-water at or
         * above the start moves it to the next block, because the high-water block was processed
         * under some earlier cursor and the request carries no event index to trim inside it.
         */
        private fun resolveStart(cursor: AlchemyCursor?, safeBlockHeight: Long): Start {
            val recordedInitial = request.checkpoint?.get(INITIAL_START_BLOCK_FIELD)
                ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
                ?.asLong()
            var block: Long
            var initialStartBlock = recordedInitial
            if (cursor != null) {
                block = cursor.nextBlock
            } else {
                block = when (properties.startMode) {
                    AlchemyStartMode.REGISTRATION_SAFE -> safeBlockHeight + 1
                    AlchemyStartMode.CONFIGURED_BLOCK -> properties.networkFor(request.chainId)?.startBlock
                        ?: throw ProviderConfigurationException(
                            "start-mode=configured-block requires ${AlchemyProviderProperties.PREFIX}.networks.${request.chainId}.start-block.",
                        )
                }
                initialStartBlock = recordedInitial ?: block
            }
            var highWaterAdjusted = false
            val highWater = request.fromBlockHeight
            if (highWater != null && highWater >= block) {
                block = highWater + 1
                highWaterAdjusted = true
            }
            return Start(block = block, initialStartBlock = initialStartBlock, highWaterAdjusted = highWaterAdjusted)
        }

        private inner class WindowScan(val events: List<ChainProviderObservedEvent>, val nextBlock: Long)

        private fun scanWindow(start: Long, safeBlockHeight: Long): WindowScan {
            val windowEnd = minOf(safeBlockHeight, start + properties.maxWindowBlocks - 1)
            val emitted = mutableListOf<ChainProviderObservedEvent>()
            var candidate = start
            var rangeEnd = windowEnd
            var blocksFullyDrained = 0L
            while (candidate <= windowEnd) {
                if (emitted.size >= request.limit) {
                    break
                }
                if (!budget.canStartBlock()) {
                    requireProgress(blocksFullyDrained, candidate)
                    break
                }
                if (rangeEnd == candidate) {
                    // The window came back paged and no boundary lies ahead of this block: drain it alone.
                    oneBlockFallbacks += 1
                    metrics?.recordAlchemyBlockFallback(network)
                    when (val block = drainBlock(candidate)) {
                        is ScanResult.Complete -> {
                            if (appendBlocks(block.events, emitted) != null) {
                                break
                            }
                            blocksFullyDrained += 1
                            candidate += 1
                            rangeEnd = windowEnd
                        }
                        ScanResult.Exhausted -> {
                            requireProgress(blocksFullyDrained, candidate)
                            break
                        }
                        is ScanResult.Paged -> error("a drained block cannot be paged")
                    }
                    continue
                }
                when (val range = scanRange(from = candidate, to = rangeEnd)) {
                    is ScanResult.Complete -> {
                        val cutoff = appendBlocks(range.events, emitted)
                        if (cutoff != null) {
                            blocksFullyDrained += cutoff - candidate
                            candidate = cutoff
                            break
                        }
                        blocksFullyDrained += rangeEnd - candidate + 1
                        candidate = rangeEnd + 1
                        rangeEnd = windowEnd
                    }
                    is ScanResult.Paged -> {
                        // Ascending order means every block before the page boundary was covered in
                        // full: re-query those as a complete range while the budget still leaves room
                        // for that attempt and for draining the boundary block afterwards.
                        rangeEnd = if (range.boundaryBlock > candidate && budget.remainingCalls >= NARROWING_RESERVE) {
                            narrowings += 1
                            metrics?.recordAlchemyNarrowing(network)
                            range.boundaryBlock - 1
                        } else {
                            candidate
                        }
                    }
                    ScanResult.Exhausted -> {
                        requireProgress(blocksFullyDrained, candidate)
                        break
                    }
                }
            }
            return WindowScan(events = emitted, nextBlock = candidate)
        }

        /**
         * Appends whole blocks in order while they fit `request.limit`; returns the first block
         * that did not fit (the next cursor position), or null when every block was appended.
         */
        private fun appendBlocks(events: List<ChainProviderObservedEvent>, emitted: MutableList<ChainProviderObservedEvent>): Long? {
            val byBlock = TreeMap<Long, MutableList<ChainProviderObservedEvent>>()
            events.forEach { byBlock.getOrPut(it.blockHeight) { mutableListOf() }.add(it) }
            for ((blockHeight, blockEvents) in byBlock) {
                if (blockEvents.size > request.limit) {
                    throw ProviderConfigurationException(
                        "Alchemy block $blockHeight has ${blockEvents.size} ERC20 events for the watched address, exceeding " +
                            "request.limit=${request.limit}; the current ChainProviderPort cannot safely split one block. " +
                            "Raise asset-sync.sync.pagination.page-size or narrow the watched scope.",
                    )
                }
                if (emitted.size + blockEvents.size > request.limit) {
                    return blockHeight
                }
                emitted += blockEvents
            }
            return null
        }

        private fun requireProgress(blocksFullyDrained: Long, candidate: Long) {
            if (blocksFullyDrained == 0L) {
                throw ChainProviderUnavailableException(
                    "Alchemy fetch budget exhausted before block $candidate was fully drained " +
                        "(${budget.rpcCalls} of ${properties.maxRpcCallsPerFetch} RPC calls, ${Duration.between(startedAt, clock.instant()).toMillis()} ms); " +
                        "the checkpoint is kept for a retry.",
                )
            }
        }

        private fun scanRange(from: Long, to: Long): ScanResult {
            val inbound = fetchStream(from = from, to = to, direction = Direction.INBOUND, pageKey = null)
                ?: return ScanResult.Exhausted
            if (inbound.pageKey != null) {
                return ScanResult.Paged(boundaryBlock = lastBlock(inbound, default = from))
            }
            val outbound = fetchStream(from = from, to = to, direction = Direction.OUTBOUND, pageKey = null)
                ?: return ScanResult.Exhausted
            if (outbound.pageKey != null) {
                return ScanResult.Paged(boundaryBlock = lastBlock(outbound, default = from))
            }
            return ScanResult.Complete(merge(inbound.outcomes, outbound.outcomes))
        }

        private fun lastBlock(page: StreamPage, default: Long): Long =
            page.outcomes.maxOfOrNull { it.blockHeight } ?: default

        private fun drainBlock(block: Long): ScanResult {
            val inbound = drainStream(block = block, direction = Direction.INBOUND) ?: return ScanResult.Exhausted
            val outbound = drainStream(block = block, direction = Direction.OUTBOUND) ?: return ScanResult.Exhausted
            return ScanResult.Complete(merge(inbound, outbound))
        }

        /** Follows `pageKey` in memory for one block and one direction; null when the budget ran out. */
        private fun drainStream(block: Long, direction: Direction): List<AlchemyTransferMapper.Outcome>? {
            val outcomes = mutableListOf<AlchemyTransferMapper.Outcome>()
            val seenPageKeys = HashSet<String>()
            val seenUniqueIds = HashSet<String>()
            var pageKey: String? = null
            while (true) {
                val page = fetchStream(from = block, to = block, direction = direction, pageKey = pageKey) ?: return null
                page.outcomes.forEach { outcome ->
                    if (!seenUniqueIds.add(outcome.uniqueId)) {
                        throw ChainProviderUnavailableException(
                            "Alchemy repeated transfer ${outcome.uniqueId} across pageKey pages of block $block; retrying from the durable cursor.",
                        )
                    }
                }
                outcomes += page.outcomes
                val next = page.pageKey ?: return outcomes
                if (!seenPageKeys.add(next)) {
                    throw ChainProviderUnavailableException(
                        "Alchemy repeated a pageKey while draining block $block; retrying from the durable cursor.",
                    )
                }
                pageKey = next
            }
        }

        /** One `alchemy_getAssetTransfers` call, mapped and window-checked; null when the budget ran out. */
        private fun fetchStream(from: Long, to: Long, direction: Direction, pageKey: String?): StreamPage? {
            if (!budget.tryConsume()) {
                return null
            }
            val params = AlchemyTransfersParams(
                fromBlock = hex(from),
                toBlock = hex(to),
                contractAddresses = listOf(assetConfig.contractAddress),
                maxCount = hex(TRANSFERS_MAX_COUNT),
                toAddress = if (direction == Direction.INBOUND) identity.address else null,
                fromAddress = if (direction == Direction.OUTBOUND) identity.address else null,
                pageKey = pageKey,
            )
            val result = client.assetTransfers(network = network, params = params, deadline = budget.hardDeadline)
            val outcomes = requireNotNull(result.transfers).map { transfer ->
                val outcome = mapper.map(transfer, direction)
                if (outcome.blockHeight < from || outcome.blockHeight > to) {
                    throw ChainProviderUnavailableException(
                        "Alchemy returned block ${outcome.blockHeight} for a $from..$to request; retrying from the durable cursor.",
                    )
                }
                outcome
            }
            return StreamPage(outcomes = outcomes, pageKey = result.pageKey?.takeIf { it.isNotBlank() })
        }

        /** Dedupes both streams by `uniqueId`, applies the skip policies, and sorts what is emitted. */
        private fun merge(
            inbound: List<AlchemyTransferMapper.Outcome>,
            outbound: List<AlchemyTransferMapper.Outcome>,
        ): List<ChainProviderObservedEvent> {
            val events = LinkedHashMap<String, ChainProviderObservedEvent>()
            (inbound + outbound).forEach { outcome ->
                when (outcome) {
                    is AlchemyTransferMapper.Outcome.SelfTransfer -> skips.selfTransfers += outcome.uniqueId
                    is AlchemyTransferMapper.Outcome.WrongToken -> skips.wrongTokenRows += outcome.uniqueId
                    is AlchemyTransferMapper.Outcome.Event -> {
                        val highWater = request.fromBlockHeight
                        if (highWater != null && outcome.blockHeight < highWater) {
                            skips.belowHighWater += 1
                        } else {
                            val previous = events.put(outcome.uniqueId, outcome.event)
                            if (previous != null && previous != outcome.event) {
                                throw ProviderDataInvalidException(
                                    "Alchemy returned conflicting rows for transfer ${outcome.uniqueId} across the inbound and outbound streams.",
                                )
                            }
                        }
                    }
                }
            }
            return events.values.sortedWith(compareBy({ it.blockHeight }, { it.eventIndex }, { it.txHash }))
        }

        private fun page(
            events: List<ChainProviderObservedEvent>,
            nextBlock: Long,
            safeBlockHeight: Long,
            finality: Finality,
            start: Start,
            mode: String,
            blocksFullyDrained: Long,
        ): ChainProviderEventsPage {
            val hasMore = nextBlock <= safeBlockHeight
            metrics?.let { meters ->
                meters.recordAlchemySkippedRows(network, SKIP_SELF_TRANSFER, skips.selfTransfers.size)
                meters.recordAlchemySkippedRows(network, SKIP_WRONG_TOKEN, skips.wrongTokenRows.size)
                meters.recordAlchemySkippedRows(network, SKIP_BELOW_HIGH_WATER, skips.belowHighWater)
            }
            val metadata = metadata(
                nextBlock = nextBlock,
                safeBlockHeight = safeBlockHeight,
                finality = finality,
                start = start,
                mode = mode,
                blocksFullyDrained = blocksFullyDrained,
            )
            logger.info(
                "alchemy_provider_page_fetch_succeeded chainId={} address={} asset={} mode={} startBlock={} nextBlock={} safe={} latest={} events={} hasMore={} rpcCalls={} oneBlockFallbacks={} narrowings={}",
                identity.chainId,
                identity.address,
                identity.asset,
                mode,
                start.block,
                nextBlock,
                safeBlockHeight,
                latestBlockHeight,
                events.size,
                hasMore,
                budget.rpcCalls,
                oneBlockFallbacks,
                narrowings,
            )
            return ChainProviderEventsPage(
                events = events,
                nextCursor = AlchemyCursor(nextBlock).encode(),
                hasMore = hasMore,
                latestBlockHeight = latestBlockHeight,
                safeBlockHeight = safeBlockHeight,
                metadata = metadata,
            )
        }

        private fun metadata(
            nextBlock: Long,
            safeBlockHeight: Long,
            finality: Finality,
            start: Start,
            mode: String,
            blocksFullyDrained: Long,
        ): ObjectNode {
            val node = objectMapper.createObjectNode()
            node.put("provider", AlchemyCursor.PROVIDER)
            node.put("cursorVersion", AlchemyCursor.VERSION)
            node.put("chainId", identity.chainId)
            node.put("network", network)
            node.put("asset", identity.asset)
            node.put("contractAddress", assetConfig.contractAddress)
            val scan = node.putObject("scan")
            scan.put("mode", mode)
            scan.put("firstCandidateBlock", start.block)
            scan.put("lastScannedBlock", nextBlock - 1)
            scan.put("blocksFullyDrained", blocksFullyDrained)
            scan.put("oneBlockFallbacks", oneBlockFallbacks)
            scan.put("narrowings", narrowings)
            scan.put("rpcCalls", budget.rpcCalls)
            node.put("nextBlock", nextBlock)
            node.put("latestBlockHeight", latestBlockHeight)
            node.put("safeBlockHeight", safeBlockHeight)
            node.put("finalityMode", properties.finalityMode.name.lowercase())
            node.put("finalityFallback", finality.fallback)
            start.initialStartBlock?.let { node.put(INITIAL_START_BLOCK_FIELD, it) }
            node.put("highWaterAdjusted", start.highWaterAdjusted)
            node.put("skippedSelfTransfers", skips.selfTransfers.size)
            node.put("skippedWrongTokenRows", skips.wrongTokenRows.size)
            node.put("skippedBelowHighWater", skips.belowHighWater)
            node.put("blockComplete", true)
            return node
        }
    }

    companion object {
        const val INITIAL_START_BLOCK_FIELD = "initialStartBlock"

        /** Alchemy's documented per-response maximum for `alchemy_getAssetTransfers`. */
        const val TRANSFERS_MAX_COUNT = 1000L

        /** Share of the provider timeout after which no new block scan starts. */
        const val SOFT_DEADLINE_FRACTION = 0.75

        /** Calls that must remain for a narrowed range attempt plus a one-block drain after it. */
        const val NARROWING_RESERVE = 4

        const val SKIP_SELF_TRANSFER = "SELF_TRANSFER"
        const val SKIP_WRONG_TOKEN = "WRONG_TOKEN"
        const val SKIP_BELOW_HIGH_WATER = "BELOW_HIGH_WATER"

        private const val MAX_ERROR_LENGTH = 240

        fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)
    }
}

enum class AlchemyProviderState {
    PROBE_SUCCEEDED,
    NO_REQUIRED_NETWORKS,
    FETCH_SUCCEEDED,
    FETCH_FAILED,
}

/**
 * The per-fetch RPC and time budget. `tryConsume` guards every call against the hard deadline
 * (the service's provider timeout); `canStartBlock` refuses to begin another block once fewer
 * than two calls remain or the soft deadline passed, so a fetch normally returns a complete prefix
 * instead of timing out mid-block.
 */
class AlchemyFetchBudget(
    private val maxRpcCalls: Int,
    private val softDeadline: Instant,
    val hardDeadline: Instant,
    private val clock: Clock,
) {

    var rpcCalls: Int = 0
        private set

    val remainingCalls: Int
        get() = maxRpcCalls - rpcCalls

    fun canStartBlock(): Boolean = remainingCalls >= 2 && clock.instant().isBefore(softDeadline)

    fun tryConsume(): Boolean {
        if (remainingCalls <= 0 || !clock.instant().isBefore(hardDeadline)) {
            return false
        }
        rpcCalls += 1
        return true
    }

    fun consumeOrThrow(what: String) {
        if (!tryConsume()) {
            throw ChainProviderUnavailableException(
                "Alchemy fetch budget exhausted before $what ($rpcCalls of $maxRpcCalls RPC calls); the checkpoint is kept for a retry.",
            )
        }
    }
}
