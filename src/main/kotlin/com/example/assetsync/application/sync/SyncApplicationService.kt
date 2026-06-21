package com.example.assetsync.application.sync

import com.example.assetsync.application.account.AccountNotFoundException
import com.example.assetsync.application.account.AccountRepository
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressRepository
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.transaction.ObservedEventApplicationService
import com.example.assetsync.domain.model.TransitionOutcome
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SyncApplicationService(
    private val accountRepository: AccountRepository,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainProviderPort: ChainProviderPort,
    private val observedEventApplicationService: ObservedEventApplicationService,
    private val syncRunLifecycleService: SyncRunLifecycleService,
    private val metrics: AssetSyncMetrics,
) {
    private val logger = LoggerFactory.getLogger(SyncApplicationService::class.java)

    fun syncAddress(addressId: UUID): SyncRun {
        val watchedAddress = watchedAddressRepository.findActiveById(addressId)
            ?: throw WatchedAddressByIdNotFoundException(addressId)
        val syncRun = syncRunLifecycleService.createStarted(SyncTargetType.ADDRESS, addressId)

        return execute(syncRun, listOf(watchedAddress))
    }

    fun syncAccount(accountId: UUID): SyncRun {
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }

        val watchedAddresses = watchedAddressRepository.findActiveByAccountId(accountId)
        val syncRun = syncRunLifecycleService.createStarted(SyncTargetType.ACCOUNT, accountId)

        return execute(syncRun, watchedAddresses)
    }

    fun getSyncRun(syncRunId: UUID): SyncRun =
        syncRunLifecycleService.get(syncRunId)

    private fun execute(syncRun: SyncRun, watchedAddresses: List<WatchedAddress>): SyncRun {
        val progress = SyncProgress()

        return try {
            watchedAddresses.forEach { watchedAddress ->
                fetchAndIngestEvents(syncRun = syncRun, watchedAddress = watchedAddress, progress = progress)
            }

            syncRunLifecycleService.markSucceeded(
                syncRunId = syncRun.id,
                eventsSeen = progress.eventsSeen,
                eventsChanged = progress.eventsChanged,
            )
        } catch (exception: ChainProviderUnavailableException) {
            val failed = syncRunLifecycleService.markFailed(
                syncRunId = syncRun.id,
                eventsSeen = progress.eventsSeen,
                eventsChanged = progress.eventsChanged,
                lastError = exception.conciseMessage(),
            )
            throw SyncProviderUnavailableException(failed)
        } catch (exception: RuntimeException) {
            syncRunLifecycleService.markFailed(
                syncRunId = syncRun.id,
                eventsSeen = progress.eventsSeen,
                eventsChanged = progress.eventsChanged,
                lastError = exception.conciseMessage(),
            )
            throw exception
        }
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
            chainProviderPort.fetchObservedEvents(watchedAddress).forEach { event ->
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

                val result = observedEventApplicationService.ingest(event.toIngestCommand())
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
}
