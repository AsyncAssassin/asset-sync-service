package com.example.assetsync.infrastructure.provider

import com.example.assetsync.application.sync.ChainProviderEventsPage
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
@Profile("local", "test")
class FakeChainProvider : ChainProviderPort {
    private val logger = LoggerFactory.getLogger(FakeChainProvider::class.java)
    private val scripts = ConcurrentHashMap<FakeChainProviderKey, List<FakeChainProviderStep>>()
    private val scriptPositions = ConcurrentHashMap<FakeChainProviderKey, AtomicInteger>()
    private val requestedKeys = CopyOnWriteArrayList<FakeChainProviderKey>()
    private val requestedPageRequests = CopyOnWriteArrayList<FakeChainProviderPageRequest>()
    private val transactionActiveSnapshots = CopyOnWriteArrayList<Boolean>()
    private val requestIdSnapshots = CopyOnWriteArrayList<String?>()

    override fun fetchObservedEventsPage(request: ChainProviderEventsPageRequest): ChainProviderEventsPage {
        val key = FakeChainProviderKey(
            chainId = request.chainId,
            address = request.address,
            asset = request.asset,
        )
        requestedKeys.add(key)
        requestedPageRequests.add(
            FakeChainProviderPageRequest(
                key = key,
                cursor = request.cursor,
                limit = request.limit,
                fromBlockHeight = request.fromBlockHeight,
                fromEventIndex = request.fromEventIndex,
            ),
        )
        recordTransactionState()
        recordRequestId()

        val steps = scripts[key].orEmpty()
        logger.info(
            "fake_provider_page_fetch_started accountId={} watchedAddressId={} chainId={} address={} asset={} cursorPresent={} limit={} scriptedSteps={}",
            request.accountId,
            request.watchedAddressId,
            request.chainId,
            request.address,
            request.asset,
            request.cursor != null,
            request.limit,
            steps.size,
        )

        val page = nextPage(key = key, steps = steps, request = request)
        logger.info(
            "fake_provider_page_fetch_completed accountId={} watchedAddressId={} chainId={} address={} asset={} events={} hasMore={} nextCursorPresent={}",
            request.accountId,
            request.watchedAddressId,
            request.chainId,
            request.address,
            request.asset,
            page.events.size,
            page.hasMore,
            page.nextCursor != null,
        )
        return page
    }

    fun setEvents(
        chainId: String,
        address: String,
        asset: String,
        events: List<ChainProviderObservedEvent>,
    ) {
        val key = FakeChainProviderKey(chainId, address, asset)
        scripts[key] = events.map { FakeChainProviderStep.Event(it) }
        scriptPositions.remove(key)
    }

    fun setScript(
        chainId: String,
        address: String,
        asset: String,
        steps: List<FakeChainProviderStep>,
    ) {
        val key = FakeChainProviderKey(chainId, address, asset)
        scripts[key] = steps
        scriptPositions.remove(key)
    }

    fun clear() {
        scripts.clear()
        scriptPositions.clear()
        requestedKeys.clear()
        requestedPageRequests.clear()
        transactionActiveSnapshots.clear()
        requestIdSnapshots.clear()
    }

    fun requestedKeys(): List<FakeChainProviderKey> =
        requestedKeys.toList()

    fun requestedPageRequests(): List<FakeChainProviderPageRequest> =
        requestedPageRequests.toList()

    fun transactionActiveSnapshots(): List<Boolean> =
        transactionActiveSnapshots.toList()

    fun requestIdSnapshots(): List<String?> =
        requestIdSnapshots.toList()

    fun scriptCount(): Int =
        scripts.size

    private fun nextPage(
        key: FakeChainProviderKey,
        steps: List<FakeChainProviderStep>,
        request: ChainProviderEventsPageRequest,
    ): ChainProviderEventsPage {
        if (request.limit <= 0) {
            throw ProviderDataInvalidException("Fake provider received a non-positive page limit.")
        }

        val storedPosition = scriptPositions.computeIfAbsent(key) { AtomicInteger(0) }
        var index = request.cursor?.toIntOrNull() ?: storedPosition.get()
        if (index >= steps.size) {
            return ChainProviderEventsPage(
                events = emptyList(),
                nextCursor = request.cursor ?: index.toString(),
                hasMore = false,
                latestBlockHeight = null,
                safeBlockHeight = null,
            )
        }

        val firstStep = steps[index]
        if (firstStep is FakeChainProviderStep.Page) {
            return scriptedPage(
                key = key,
                position = storedPosition,
                index = index,
                request = request,
                page = firstStep.page,
            )
        }

        val events = mutableListOf<ChainProviderObservedEvent>()
        while (index < steps.size && events.size < request.limit) {
            recordTransactionState()
            recordRequestId()
            when (val step = steps[index]) {
                is FakeChainProviderStep.Event -> {
                    events += step.event
                    index += 1
                }
                is FakeChainProviderStep.Delay -> {
                    sleep(step.duration)
                    index += 1
                }
                is FakeChainProviderStep.Failure -> {
                    if (events.isEmpty()) {
                        throw ChainProviderUnavailableException(step.message)
                    }
                    break
                }
                is FakeChainProviderStep.ThrowableFailure -> {
                    if (events.isEmpty()) {
                        throw step.throwable
                    }
                    break
                }
                is FakeChainProviderStep.Page -> break
            }
        }

        storedPosition.set(index)
        val hasMore = index < steps.size
        val nextCursor = index.toString()
        val latestBlockHeight = events.maxOfOrNull { it.blockHeight }
        return ChainProviderEventsPage(
            events = events,
            nextCursor = nextCursor,
            hasMore = hasMore,
            latestBlockHeight = latestBlockHeight,
            safeBlockHeight = latestBlockHeight,
        )
    }

    private fun scriptedPage(
        key: FakeChainProviderKey,
        position: AtomicInteger,
        index: Int,
        request: ChainProviderEventsPageRequest,
        page: FakeChainProviderPage,
    ): ChainProviderEventsPage {
        if (page.expectedCursor != request.cursor) {
            throw ProviderDataInvalidException("Fake provider expected cursor ${page.expectedCursor} but received ${request.cursor}.")
        }
        position.set(index + 1)
        logger.debug(
            "fake_provider_scripted_page key={} expectedCursor={} events={} hasMore={}",
            key,
            page.expectedCursor,
            page.events.size,
            page.hasMore,
        )
        return ChainProviderEventsPage(
            events = page.events,
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
            latestBlockHeight = page.latestBlockHeight,
            safeBlockHeight = page.safeBlockHeight,
            metadata = page.metadata,
        )
    }

    private fun sleep(duration: Duration) {
        try {
            Thread.sleep(duration.toMillis())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChainProviderUnavailableException("Provider fetch interrupted.", exception)
        }
    }

    private fun recordTransactionState() {
        transactionActiveSnapshots.add(TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun recordRequestId() {
        requestIdSnapshots.add(MDC.get("requestId"))
    }
}

@Component
@Profile("local", "test")
class FakeChainProviderHealthIndicator(
    private val fakeChainProvider: FakeChainProvider,
) : HealthIndicator {

    override fun health(): Health =
        Health
            .up()
            .withDetail("provider", "fake")
            .withDetail("scriptedKeys", fakeChainProvider.scriptCount())
            .build()
}

data class FakeChainProviderKey(
    val chainId: String,
    val address: String,
    val asset: String,
)

data class FakeChainProviderPageRequest(
    val key: FakeChainProviderKey,
    val cursor: String?,
    val limit: Int,
    val fromBlockHeight: Long? = null,
    val fromEventIndex: Int? = null,
)

data class FakeChainProviderPage(
    val expectedCursor: String?,
    val events: List<ChainProviderObservedEvent>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val latestBlockHeight: Long? = null,
    val safeBlockHeight: Long? = null,
    val metadata: ObjectNode? = null,
)

sealed interface FakeChainProviderStep {
    data class Page(
        val page: FakeChainProviderPage,
    ) : FakeChainProviderStep

    data class Event(
        val event: ChainProviderObservedEvent,
    ) : FakeChainProviderStep

    data class Failure(
        val message: String = "Provider is unavailable.",
    ) : FakeChainProviderStep

    data class ThrowableFailure(
        val throwable: Throwable,
    ) : FakeChainProviderStep

    data class Delay(
        val duration: Duration,
    ) : FakeChainProviderStep
}
