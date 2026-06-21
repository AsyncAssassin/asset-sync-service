package com.example.assetsync.infrastructure.provider

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class FakeChainProvider : ChainProviderPort {
    private val logger = LoggerFactory.getLogger(FakeChainProvider::class.java)
    private val scripts = ConcurrentHashMap<FakeChainProviderKey, List<FakeChainProviderStep>>()
    private val requestedKeys = CopyOnWriteArrayList<FakeChainProviderKey>()
    private val transactionActiveSnapshots = CopyOnWriteArrayList<Boolean>()

    override fun fetchObservedEvents(watchedAddress: WatchedAddress): Sequence<ChainProviderObservedEvent> {
        val key = FakeChainProviderKey(
            chainId = watchedAddress.chainId,
            address = watchedAddress.address,
            asset = watchedAddress.asset,
        )
        requestedKeys.add(key)
        recordTransactionState()
        val steps = scripts[key].orEmpty()
        logger.info(
            "fake_provider_fetch_started accountId={} watchedAddressId={} chainId={} address={} asset={} scriptedSteps={}",
            watchedAddress.accountId,
            watchedAddress.id,
            watchedAddress.chainId,
            watchedAddress.address,
            watchedAddress.asset,
            steps.size,
        )

        return sequence {
            var eventsEmitted = 0
            steps.forEach { step ->
                recordTransactionState()
                when (step) {
                    is FakeChainProviderStep.Event -> {
                        logger.debug(
                            "fake_provider_event_emitted accountId={} watchedAddressId={} chainId={} address={} asset={} txHash={} eventIndex={} status={}",
                            watchedAddress.accountId,
                            watchedAddress.id,
                            step.event.chainId,
                            step.event.address,
                            step.event.asset,
                            step.event.txHash,
                            step.event.eventIndex,
                            step.event.status,
                        )
                        eventsEmitted += 1
                        yield(step.event)
                    }
                    is FakeChainProviderStep.Failure -> {
                        logger.warn(
                            "fake_provider_fetch_failed accountId={} watchedAddressId={} chainId={} address={} asset={} eventsEmitted={} error={}",
                            watchedAddress.accountId,
                            watchedAddress.id,
                            watchedAddress.chainId,
                            watchedAddress.address,
                            watchedAddress.asset,
                            eventsEmitted,
                            step.message.concise(),
                        )
                        throw ChainProviderUnavailableException(step.message)
                    }
                }
            }
            logger.info(
                "fake_provider_fetch_completed accountId={} watchedAddressId={} chainId={} address={} asset={} eventsEmitted={}",
                watchedAddress.accountId,
                watchedAddress.id,
                watchedAddress.chainId,
                watchedAddress.address,
                watchedAddress.asset,
                eventsEmitted,
            )
        }
    }

    fun setEvents(
        chainId: String,
        address: String,
        asset: String,
        events: List<ChainProviderObservedEvent>,
    ) {
        scripts[FakeChainProviderKey(chainId, address, asset)] = events.map { FakeChainProviderStep.Event(it) }
    }

    fun setScript(
        chainId: String,
        address: String,
        asset: String,
        steps: List<FakeChainProviderStep>,
    ) {
        scripts[FakeChainProviderKey(chainId, address, asset)] = steps
    }

    fun clear() {
        scripts.clear()
        requestedKeys.clear()
        transactionActiveSnapshots.clear()
    }

    fun requestedKeys(): List<FakeChainProviderKey> =
        requestedKeys.toList()

    fun transactionActiveSnapshots(): List<Boolean> =
        transactionActiveSnapshots.toList()

    fun scriptCount(): Int =
        scripts.size

    private fun recordTransactionState() {
        transactionActiveSnapshots.add(TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun String.concise(): String =
        replace(Regex("\\s+"), " ").take(240)
}

@Component
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

sealed interface FakeChainProviderStep {
    data class Event(
        val event: ChainProviderObservedEvent,
    ) : FakeChainProviderStep

    data class Failure(
        val message: String = "Provider is unavailable.",
    ) : FakeChainProviderStep
}
