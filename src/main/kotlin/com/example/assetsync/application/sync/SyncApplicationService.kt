package com.example.assetsync.application.sync

import com.example.assetsync.application.account.AccountNotFoundException
import com.example.assetsync.application.account.AccountRepository
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressRepository
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.transaction.ObservedEventApplicationService
import com.example.assetsync.application.transaction.ObservedTransactionConflictException
import com.example.assetsync.application.transaction.WatchedAddressNotFoundException
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.domain.model.TransitionOutcome
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service

@Service
class SyncApplicationService(
    private val accountRepository: AccountRepository,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainProviderPort: ChainProviderPort,
    private val observedEventApplicationService: ObservedEventApplicationService,
    private val syncRunLifecycleService: SyncRunLifecycleService,
    private val metrics: AssetSyncMetrics,
    private val syncProviderExecutor: ExecutorService,
    private val syncCapacitySemaphore: Semaphore,
    private val syncProperties: SyncProperties,
) {
    private val logger = LoggerFactory.getLogger(SyncApplicationService::class.java)

    fun syncAddress(addressId: UUID): SyncRun {
        val watchedAddress = watchedAddressRepository.findActiveById(addressId)
            ?: throw WatchedAddressByIdNotFoundException(addressId)
        return withCapacityPermit {
            val syncRun = syncRunLifecycleService.createStarted(SyncTargetType.ADDRESS, addressId)
            execute(syncRun, listOf(watchedAddress))
        }
    }

    fun syncAccount(accountId: UUID): SyncRun {
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }
        return withCapacityPermit {
            val syncRun = syncRunLifecycleService.createStarted(SyncTargetType.ACCOUNT, accountId)
            executeAccount(syncRun, accountId)
        }
    }

    // Admission gate before a run is created: an over-cap sync is rejected cleanly (429) with no
    // orphan STARTED/FAILED row. The provider-pool AbortPolicy remains a backstop (e.g. on a leak).
    private fun <T> withCapacityPermit(block: () -> T): T {
        if (!syncCapacitySemaphore.tryAcquire()) {
            throw SyncCapacityExceededException(syncProperties.providerMaxThreads)
        }
        return try {
            block()
        } finally {
            syncCapacitySemaphore.release()
        }
    }

    fun getSyncRun(syncRunId: UUID): SyncRun =
        syncRunLifecycleService.get(syncRunId)

    private fun execute(syncRun: SyncRun, watchedAddresses: List<WatchedAddress>): SyncRun =
        executeWithProgress(syncRun) { progress ->
            watchedAddresses.forEach { watchedAddress ->
                fetchAndIngestEvents(syncRun = syncRun, watchedAddress = watchedAddress, progress = progress)
            }
        }

    private fun executeAccount(syncRun: SyncRun, accountId: UUID): SyncRun =
        executeWithProgress(syncRun) { progress ->
            var processed = 0
            while (true) {
                if (processed >= syncProperties.maxAccountSyncAddresses) {
                    val overflow = watchedAddressRepository.findActiveByAccountId(
                        accountId = accountId,
                        limit = 1,
                        offset = processed,
                    )
                    if (overflow.isNotEmpty()) {
                        throw AccountSyncTooLargeException(
                            accountId = accountId,
                            maxAddresses = syncProperties.maxAccountSyncAddresses,
                        )
                    }
                    break
                }

                val limit = minOf(
                    syncProperties.accountSyncBatchSize,
                    syncProperties.maxAccountSyncAddresses - processed,
                )
                val batch = watchedAddressRepository.findActiveByAccountId(
                    accountId = accountId,
                    limit = limit,
                    offset = processed,
                )
                if (batch.isEmpty()) {
                    break
                }

                batch.forEach { watchedAddress ->
                    fetchAndIngestEvents(syncRun = syncRun, watchedAddress = watchedAddress, progress = progress)
                }
                processed += batch.size

                if (batch.size < limit) {
                    break
                }
            }
        }

    private fun executeWithProgress(syncRun: SyncRun, body: (SyncProgress) -> Unit): SyncRun {
        val progress = SyncProgress()

        return try {
            body(progress)
            syncRunLifecycleService.markSucceeded(
                syncRunId = syncRun.id,
                eventsSeen = progress.eventsSeen,
                eventsChanged = progress.eventsChanged,
            )
        } catch (exception: ChainProviderUnavailableException) {
            val failed = markFailedPreserving(exception, syncRun, progress) ?: syncRun
            throw SyncProviderUnavailableException(failed, exception)
        } catch (exception: RuntimeException) {
            markFailedPreserving(exception, syncRun, progress)
            throw exception
        } catch (throwable: Throwable) {
            markFailedPreserving(throwable, syncRun, progress)
            throw throwable
        }
    }

    private fun markFailedPreserving(
        original: Throwable,
        syncRun: SyncRun,
        progress: SyncProgress,
    ): SyncRun? =
        try {
            syncRunLifecycleService.markFailed(
                syncRunId = syncRun.id,
                eventsSeen = progress.eventsSeen,
                eventsChanged = progress.eventsChanged,
                lastError = original.conciseMessage(),
            )
        } catch (markFailedException: Throwable) {
            original.addSuppressed(markFailedException)
            logger.error(
                "sync_run_mark_failed_failed syncRunId={} targetType={} targetId={} originalError={} markError={}",
                syncRun.id,
                syncRun.targetType,
                syncRun.targetId,
                original.conciseMessage(),
                markFailedException.conciseMessage(),
            )
            null
        }

    private fun fetchAndIngestEvents(
        syncRun: SyncRun,
        watchedAddress: WatchedAddress,
        progress: SyncProgress,
    ) {
        metrics.recordProviderFetchAttempt(syncRun.targetType)
        val sample = metrics.startProviderFetchTimer()
        var addressEventsSeen = 0
        var addressEventsChanged = 0

        logger.info(
            "provider_fetch_started syncRunId={} targetType={} targetId={} accountId={} watchedAddressId={} chainId={} address={} asset={}",
            syncRun.id,
            syncRun.targetType,
            syncRun.targetId,
            watchedAddress.accountId,
            watchedAddress.id,
            watchedAddress.chainId,
            watchedAddress.address,
            watchedAddress.asset,
        )

        try {
            forEachProviderEvent(watchedAddress) { event ->
                addressEventsSeen += 1
                progress.eventsSeen += 1
                logger.debug(
                    "provider_event_received syncRunId={} targetType={} watchedAddressId={} chainId={} address={} asset={} txHash={} eventIndex={} status={}",
                    syncRun.id,
                    syncRun.targetType,
                    watchedAddress.id,
                    event.chainId,
                    event.address,
                    event.asset,
                    event.txHash,
                    event.eventIndex,
                    event.status,
                )

                val result = try {
                    observedEventApplicationService.ingest(event.toIngestCommand())
                } catch (exception: WatchedAddressNotFoundException) {
                    // The provider returned an event whose target isn't watched — a provider-data
                    // problem, not the sync caller's fault; surface it as upstream (502), not 404.
                    throw ProviderDataMismatchException("Provider returned an event for an address that is not watched.", exception)
                } catch (exception: ObservedTransactionConflictException) {
                    throw ProviderDataMismatchException("Provider returned an event that conflicts with stored immutable fields.", exception)
                }
                if (result.result == TransitionOutcome.CREATED || result.result == TransitionOutcome.UPDATED) {
                    addressEventsChanged += 1
                    progress.eventsChanged += 1
                }
            }
        } catch (exception: RuntimeException) {
            metrics.recordProviderFetchFailure(targetType = syncRun.targetType, sample = sample)
            logger.warn(
                "provider_fetch_failed syncRunId={} targetType={} targetId={} accountId={} watchedAddressId={} chainId={} address={} asset={} eventsFetched={} error={}",
                syncRun.id,
                syncRun.targetType,
                syncRun.targetId,
                watchedAddress.accountId,
                watchedAddress.id,
                watchedAddress.chainId,
                watchedAddress.address,
                watchedAddress.asset,
                addressEventsSeen,
                exception.conciseMessage(),
            )
            throw exception
        }

        metrics.recordProviderFetchSuccess(targetType = syncRun.targetType, sample = sample)
        logger.info(
            "provider_fetch_succeeded syncRunId={} targetType={} targetId={} accountId={} watchedAddressId={} chainId={} address={} asset={} eventsFetched={} eventsChanged={}",
            syncRun.id,
            syncRun.targetType,
            syncRun.targetId,
            watchedAddress.accountId,
            watchedAddress.id,
            watchedAddress.chainId,
            watchedAddress.address,
            watchedAddress.asset,
            addressEventsSeen,
            addressEventsChanged,
        )
    }

    private fun forEachProviderEvent(
        watchedAddress: WatchedAddress,
        handler: (ChainProviderObservedEvent) -> Unit,
    ) {
        val queue = LinkedBlockingQueue<ProviderFetchItem>(PROVIDER_FETCH_QUEUE_CAPACITY)
        val providerTimeout = syncProperties.providerTimeout
        val deadlineNanos = System.nanoTime() + providerTimeout.toNanos()
        val mdcContext = MDC.getCopyOfContextMap()
        val future = try {
            syncProviderExecutor.submit {
                withMdcContext(mdcContext) {
                    try {
                        for (event in chainProviderPort.fetchObservedEvents(watchedAddress)) {
                            // Bounded offer, never a blocking put: if the consumer is gone (deadline
                            // passed or it aborted and cancelled us), stop instead of blocking forever.
                            if (!offerUntilDeadline(queue, ProviderFetchItem.Event(event), deadlineNanos)) {
                                return@withMdcContext
                            }
                        }
                        offerUntilDeadline(queue, ProviderFetchItem.Complete, deadlineNanos)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (exception: Throwable) {
                        // Non-blocking: the consumer may already be gone; never re-block on a full queue.
                        queue.offer(ProviderFetchItem.Failure(exception))
                    }
                }
            }
        } catch (exception: RejectedExecutionException) {
            throw SyncCapacityExceededException(syncProperties.providerMaxThreads)
        }

        while (true) {
            val remainingNanos = remainingNanosUntil(deadlineNanos)
            if (remainingNanos <= 0) {
                future.cancel(true)
                throw ChainProviderUnavailableException("Provider timeout after $providerTimeout.")
            }

            val item = try {
                queue.poll(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                future.cancel(true)
                throw ChainProviderUnavailableException("Provider fetch interrupted.", exception)
            }

            when (item) {
                null -> {
                    future.cancel(true)
                    throw ChainProviderUnavailableException("Provider timeout after $providerTimeout.")
                }
                is ProviderFetchItem.Event -> {
                    try {
                        handler(item.event)
                    } catch (exception: RuntimeException) {
                        future.cancel(true)
                        throw exception
                    }
                    if (remainingNanosUntil(deadlineNanos) <= 0) {
                        future.cancel(true)
                        throw ChainProviderUnavailableException("Provider timeout after $providerTimeout.")
                    }
                }
                is ProviderFetchItem.Failure -> {
                    when (val cause = item.exception) {
                        is RuntimeException -> throw cause
                        else -> throw ChainProviderUnavailableException(cause.message ?: "Provider fetch failed.", cause)
                    }
                }
                ProviderFetchItem.Complete -> return
            }
        }
    }

    private fun remainingNanosUntil(deadlineNanos: Long): Long =
        deadlineNanos - System.nanoTime()

    /**
     * Enqueues [item] within the remaining deadline using a bounded [LinkedBlockingQueue.offer].
     * Returns false if the deadline has passed (so the producer stops rather than blocking on a full,
     * consumer-less queue). Propagates [InterruptedException] so a cancelled producer unwinds promptly.
     */
    private fun offerUntilDeadline(
        queue: LinkedBlockingQueue<ProviderFetchItem>,
        item: ProviderFetchItem,
        deadlineNanos: Long,
    ): Boolean {
        val remaining = remainingNanosUntil(deadlineNanos)
        if (remaining <= 0) {
            return false
        }
        return queue.offer(item, remaining, TimeUnit.NANOSECONDS)
    }

    private fun <T> withMdcContext(contextMap: Map<String, String>?, block: () -> T): T {
        val previousContext = MDC.getCopyOfContextMap()
        if (contextMap == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(contextMap)
        }
        return try {
            block()
        } finally {
            if (previousContext == null) {
                MDC.clear()
            } else {
                MDC.setContextMap(previousContext)
            }
        }
    }

    private fun Throwable.conciseMessage(): String {
        val raw = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
        return raw
            .replace(Regex("\\s+"), " ")
            .take(240)
    }

    private data class SyncProgress(
        var eventsSeen: Int = 0,
        var eventsChanged: Int = 0,
    )

    private sealed interface ProviderFetchItem {
        data class Event(val event: ChainProviderObservedEvent) : ProviderFetchItem
        data class Failure(val exception: Throwable) : ProviderFetchItem
        data object Complete : ProviderFetchItem
    }

    private companion object {
        const val PROVIDER_FETCH_QUEUE_CAPACITY = 100
    }
}

class AccountSyncTooLargeException(
    val accountId: UUID,
    val maxAddresses: Int,
) : RuntimeException("Account has more than $maxAddresses active watched addresses.")

class SyncCapacityExceededException(
    val maxConcurrentSyncs: Int,
) : RuntimeException("Sync capacity exceeded: at most $maxConcurrentSyncs concurrent provider fetches.")

class ProviderDataMismatchException(
    override val message: String,
    override val cause: Throwable,
) : RuntimeException(message, cause)
