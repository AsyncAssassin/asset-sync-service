package com.example.assetsync.application.sync

import com.example.assetsync.application.account.AccountNotFoundException
import com.example.assetsync.application.account.AccountRepository
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressRepository
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.transaction.ObservedEventApplicationService
import com.example.assetsync.application.transaction.ObservedTransactionConflictException
import com.example.assetsync.application.transaction.WatchedAddressNotFoundException
import com.example.assetsync.config.SyncHeartbeatScheduler
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.domain.model.TransitionOutcome
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class SyncApplicationService(
    private val accountRepository: AccountRepository,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val syncCursorRepository: SyncCursorRepository,
    private val chainProviderPort: ChainProviderPort,
    private val observedEventApplicationService: ObservedEventApplicationService,
    private val syncRunLifecycleService: SyncRunLifecycleService,
    private val metrics: AssetSyncMetrics,
    private val syncProviderExecutor: ExecutorService,
    private val syncHeartbeatScheduler: SyncHeartbeatScheduler,
    private val syncProperties: SyncProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(SyncApplicationService::class.java)

    fun syncAddress(addressId: UUID): SyncRun {
        watchedAddressRepository.findActiveById(addressId)
            ?: throw WatchedAddressByIdNotFoundException(addressId)
        return syncRunLifecycleService.createQueued(SyncTargetType.ADDRESS, addressId)
    }

    fun syncAccount(accountId: UUID): SyncRun {
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }
        return syncRunLifecycleService.createQueued(SyncTargetType.ACCOUNT, accountId)
    }

    fun getSyncRun(syncRunId: UUID): SyncRun =
        syncRunLifecycleService.get(syncRunId)

    fun executeClaimedSyncRun(claim: ClaimedSyncRun) {
        val progress = SyncProgress(
            eventsSeen = claim.run.eventsSeen,
            eventsChanged = claim.run.eventsChanged,
        )
        val runBudget = RunBudget(
            startedAt = Instant.now(clock),
            pagination = syncProperties.pagination,
            accountScoped = claim.run.targetType == SyncTargetType.ACCOUNT,
        )
        val heartbeat = startHeartbeat(claim)
        try {
            val outcome = when (claim.run.targetType) {
                SyncTargetType.ADDRESS -> executeAddress(claim = claim, progress = progress, runBudget = runBudget)
                SyncTargetType.ACCOUNT -> executeAccount(claim = claim, progress = progress, runBudget = runBudget)
            }
            when (outcome) {
                SyncClaimOutcome.Done ->
                    syncRunLifecycleService.markSucceeded(
                        claim = claim,
                        eventsSeen = progress.eventsSeen,
                        eventsChanged = progress.eventsChanged,
                    )
                is SyncClaimOutcome.Continuation ->
                    syncRunLifecycleService.requeueContinuation(
                        claim = claim,
                        eventsSeen = progress.eventsSeen,
                        eventsChanged = progress.eventsChanged,
                        reason = outcome.reason,
                        runCheckpoint = outcome.runCheckpoint,
                        delay = outcome.delay,
                    )
            }
        } catch (throwable: Throwable) {
            if (throwable is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            handleClaimFailure(claim = claim, progress = progress, throwable = throwable)
        } finally {
            heartbeat.cancel(false)
        }
    }

    private fun executeAddress(
        claim: ClaimedSyncRun,
        progress: SyncProgress,
        runBudget: RunBudget,
    ): SyncClaimOutcome {
        val watchedAddress = watchedAddressRepository.findActiveById(claim.run.targetId)
            ?: throw WatchedAddressByIdNotFoundException(claim.run.targetId)
        return when (processAddressWithinClaim(claim = claim, watchedAddress = watchedAddress, progress = progress, runBudget = runBudget)) {
            AddressSyncOutcome.Done -> SyncClaimOutcome.Done
            AddressSyncOutcome.LeaseBusy -> SyncClaimOutcome.Continuation(
                reason = SyncRunRequeueReason.LEASE_BUSY,
                runCheckpoint = claim.run.runCheckpoint.deepCopy(),
                delay = syncProperties.pagination.cursorLeaseRetryDelay,
            )
            AddressSyncOutcome.Continuation -> SyncClaimOutcome.Continuation(
                reason = SyncRunRequeueReason.CONTINUATION,
                runCheckpoint = claim.run.runCheckpoint.deepCopy(),
                delay = syncProperties.pagination.continuationRequeueDelay,
            )
        }
    }

    private fun executeAccount(
        claim: ClaimedSyncRun,
        progress: SyncProgress,
        runBudget: RunBudget,
    ): SyncClaimOutcome {
        val accountId = claim.run.targetId
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }

        val activeAddressCount = watchedAddressRepository.countActiveByAccountId(accountId)
        if (activeAddressCount > syncProperties.maxAccountSyncAddresses) {
            throw AccountSyncTooLargeException(
                accountId = accountId,
                maxAddresses = syncProperties.maxAccountSyncAddresses,
            )
        }
        if (activeAddressCount == 0) {
            return SyncClaimOutcome.Done
        }

        var skippedBusyLeases = 0
        var visitedCount = 0
        var continuationNeeded = false
        var accountWrapped = false
        var accountNextOffset = Math.floorMod(
            claim.run.runCheckpoint.path(ACCOUNT_NEXT_OFFSET_FIELD).asInt(0),
            activeAddressCount,
        )
        val startOffset = accountNextOffset

        while (visitedCount < activeAddressCount) {
            if (runBudget.accountBudgetExceeded(progress) || runBudget.durationExceeded(Instant.now(clock))) {
                continuationNeeded = true
                break
            }

            val remainingToVisit = activeAddressCount - visitedCount
            val offset = accountNextOffset % activeAddressCount
            val limit = minOf(syncProperties.accountSyncBatchSize, remainingToVisit)
            val firstLimit = minOf(limit, activeAddressCount - offset)
            val batch = mutableListOf<Pair<Int, WatchedAddress>>()

            val firstSlice = watchedAddressRepository.findActiveByAccountId(
                accountId = accountId,
                limit = firstLimit,
                offset = offset,
            )
            firstSlice.forEachIndexed { index, watchedAddress ->
                batch += (offset + index) to watchedAddress
            }

            if (firstSlice.size < limit && visitedCount + batch.size < activeAddressCount) {
                accountWrapped = true
                val wrappedLimit = minOf(
                    limit - firstSlice.size,
                    activeAddressCount - visitedCount - batch.size,
                    startOffset,
                )
                if (wrappedLimit > 0) {
                    val wrappedSlice = watchedAddressRepository.findActiveByAccountId(
                        accountId = accountId,
                        limit = wrappedLimit,
                        offset = 0,
                    )
                    wrappedSlice.forEachIndexed { index, watchedAddress ->
                        batch += index to watchedAddress
                    }
                }
            }

            if (batch.isEmpty()) {
                break
            }

            for ((currentOffset, watchedAddress) in batch) {
                when (
                    processAddressWithinClaim(
                        claim = claim,
                        watchedAddress = watchedAddress,
                        progress = progress,
                        runBudget = runBudget,
                    )
                ) {
                    AddressSyncOutcome.Done -> Unit
                    AddressSyncOutcome.LeaseBusy -> skippedBusyLeases += 1
                    AddressSyncOutcome.Continuation -> continuationNeeded = true
                }

                visitedCount += 1
                progress.addressesVisitedThisClaim += 1
                accountNextOffset = (currentOffset + 1) % activeAddressCount
                accountWrapped = accountWrapped || accountNextOffset == 0

                if (
                    continuationNeeded ||
                    (
                        visitedCount < activeAddressCount &&
                            (runBudget.accountBudgetExceeded(progress) || runBudget.durationExceeded(Instant.now(clock)))
                        )
                ) {
                    continuationNeeded = true
                    break
                }
            }

            if (continuationNeeded) {
                break
            }
        }

        val runCheckpoint = accountTraversalCheckpoint(
            accountNextOffset = accountNextOffset,
            skippedBusyLeases = skippedBusyLeases,
            accountWrapped = accountWrapped,
        )

        return when {
            continuationNeeded -> SyncClaimOutcome.Continuation(
                reason = SyncRunRequeueReason.CONTINUATION,
                runCheckpoint = runCheckpoint,
                delay = syncProperties.pagination.continuationRequeueDelay,
            )
            skippedBusyLeases > 0 -> SyncClaimOutcome.Continuation(
                reason = SyncRunRequeueReason.LEASE_BUSY,
                runCheckpoint = runCheckpoint,
                delay = if (skippedBusyLeases == visitedCount) {
                    syncProperties.pagination.cursorLeaseRetryDelay
                } else {
                    syncProperties.pagination.continuationRequeueDelay
                },
            )
            else -> SyncClaimOutcome.Done
        }
    }

    private fun processAddressWithinClaim(
        claim: ClaimedSyncRun,
        watchedAddress: WatchedAddress,
        progress: SyncProgress,
        runBudget: RunBudget,
    ): AddressSyncOutcome {
        val ensureNow = Instant.now(clock)
        syncCursorRepository.ensureCursor(watchedAddressId = watchedAddress.id, now = ensureNow)

        val cursorLockToken = UUID.randomUUID()
        val leaseNow = Instant.now(clock)
        val lease = syncCursorRepository.tryAcquireCursorLease(
            watchedAddressId = watchedAddress.id,
            lockedBy = claim.lockedBy,
            lockToken = cursorLockToken,
            now = leaseNow,
            leaseUntil = leaseNow.plus(syncProperties.pagination.cursorLeaseDuration),
        ) ?: run {
            metrics.recordCursorLease("BUSY")
            logger.info(
                "cursor_lease_busy syncRunId={} targetType={} watchedAddressId={} chainId={} address={} asset={}",
                claim.run.id,
                claim.run.targetType,
                watchedAddress.id,
                watchedAddress.chainId,
                watchedAddress.address,
                watchedAddress.asset,
            )
            return AddressSyncOutcome.LeaseBusy
        }
        metrics.recordCursorLease("ACQUIRED")

        logger.info(
            "cursor_lease_acquired syncRunId={} targetType={} watchedAddressId={} version={} lockedBy={}",
            claim.run.id,
            claim.run.targetType,
            watchedAddress.id,
            lease.cursor.version,
            claim.lockedBy,
        )

        var current = lease.cursor
        var addressPagesThisClaim = 0
        var addressEventsSeenThisClaim = 0
        val cursorHeartbeat = startCursorLeaseHeartbeat(
            watchedAddressId = watchedAddress.id,
            lockedBy = claim.lockedBy,
            lockToken = cursorLockToken,
        )

        try {
            while (true) {
                cursorHeartbeat.throwIfFailed()
                if (runBudget.durationExceeded(Instant.now(clock)) && addressPagesThisClaim > 0) {
                    return AddressSyncOutcome.Continuation
                }

                val request = ChainProviderEventsPageRequest(
                    watchedAddressId = watchedAddress.id,
                    accountId = watchedAddress.accountId,
                    chainId = watchedAddress.chainId,
                    address = watchedAddress.address,
                    asset = watchedAddress.asset,
                    cursor = current.providerCursor,
                    limit = syncProperties.pagination.pageSize,
                    fromBlockHeight = current.lastProcessedBlockHeight,
                    safeBlockHeight = current.lastFinalizedBlockHeight,
                    checkpoint = current.checkpoint,
                )

                val page = fetchProviderPage(
                    claim = claim,
                    watchedAddress = watchedAddress,
                    request = request,
                    current = current,
                )

                cursorHeartbeat.throwIfFailed()
                val pageChanges = ingestWholePage(page.events, progress)
                addressEventsSeenThisClaim += page.events.size

                cursorHeartbeat.throwIfFailed()
                extendCursorLeaseOrThrow(
                    watchedAddressId = watchedAddress.id,
                    lockedBy = claim.lockedBy,
                    lockToken = cursorLockToken,
                )
                cursorHeartbeat.throwIfFailed()

                val checkpoint = page.metadata?.deepCopy() ?: current.checkpoint.deepCopy()
                val highWater = resolveDurableHighWater(current = current, page = page)
                val providerCursor = resolveDurableProviderCursor(page = page)
                val advanceNow = Instant.now(clock)
                val advanced = syncCursorRepository.advanceCheckpointFenced(
                    AdvanceSyncCheckpointCommand(
                        watchedAddressId = watchedAddress.id,
                        lockedBy = claim.lockedBy,
                        lockToken = cursorLockToken,
                        expectedVersion = current.version,
                        leaseCheckedAt = advanceNow,
                        providerCursor = providerCursor,
                        checkpoint = checkpoint,
                        lastProcessedBlockHeight = highWater.lastProcessedBlockHeight,
                        lastProcessedEventIndex = highWater.lastProcessedEventIndex,
                        lastFinalizedBlockHeight = highWater.lastFinalizedBlockHeight,
                        cursorUpdatedAt = advanceNow,
                        updatedAt = advanceNow,
                    ),
                )
                if (advanced == null) {
                    metrics.recordCursorCheckpoint("STALE")
                    throw CursorCheckpointAdvanceStaleException(watchedAddress.id)
                }
                metrics.recordCursorCheckpoint("ADVANCED")

                current = advanced
                addressPagesThisClaim += 1
                progress.accountPagesThisClaim += 1

                logger.info(
                    "cursor_checkpoint_advanced syncRunId={} targetType={} watchedAddressId={} version={} events={} changed={} hasMore={}",
                    claim.run.id,
                    claim.run.targetType,
                    watchedAddress.id,
                    current.version,
                    page.events.size,
                    pageChanges,
                    page.hasMore,
                )

                val heartbeatOk = syncRunLifecycleService.heartbeat(claim)
                if (!heartbeatOk) {
                    throw SyncRunClaimLostException(claim.run.id)
                }
                cursorHeartbeat.throwIfFailed()

                if (!page.hasMore) {
                    return AddressSyncOutcome.Done
                }

                if (
                    addressPagesThisClaim >= syncProperties.pagination.maxPagesPerAddressRun ||
                    addressEventsSeenThisClaim >= syncProperties.pagination.maxEventsPerAddressRun ||
                    runBudget.accountBudgetExceeded(progress) ||
                    runBudget.durationExceeded(Instant.now(clock))
                ) {
                    return AddressSyncOutcome.Continuation
                }
            }
        } finally {
            cursorHeartbeat.cancel()
            val released = syncCursorRepository.releaseCursorLeaseFenced(
                watchedAddressId = watchedAddress.id,
                lockedBy = claim.lockedBy,
                lockToken = cursorLockToken,
                updatedAt = Instant.now(clock),
            )
            metrics.recordCursorLease(if (released) "RELEASED" else "LOST")
            logger.debug(
                "cursor_lease_released syncRunId={} targetType={} watchedAddressId={} released={}",
                claim.run.id,
                claim.run.targetType,
                watchedAddress.id,
                released,
            )
        }
    }

    private fun fetchProviderPage(
        claim: ClaimedSyncRun,
        watchedAddress: WatchedAddress,
        request: ChainProviderEventsPageRequest,
        current: SyncCursor,
    ): ChainProviderEventsPage {
        metrics.recordProviderFetchAttempt(claim.run.targetType)
        val sample = metrics.startProviderFetchTimer()
        logger.info(
            "provider_page_fetch_started syncRunId={} targetType={} targetId={} accountId={} watchedAddressId={} chainId={} address={} asset={} limit={} cursorPresent={}",
            claim.run.id,
            claim.run.targetType,
            claim.run.targetId,
            watchedAddress.accountId,
            watchedAddress.id,
            watchedAddress.chainId,
            watchedAddress.address,
            watchedAddress.asset,
            request.limit,
            request.cursor != null,
        )

        return try {
            val page = fetchProviderPageWithTimeout(request)
            validatePage(request = request, page = page, current = current)
            metrics.recordProviderPage(targetType = claim.run.targetType, result = "SUCCEEDED")
            metrics.recordProviderPageEvents(targetType = claim.run.targetType, count = page.events.size)
            metrics.recordProviderFetchSuccess(targetType = claim.run.targetType, sample = sample)
            logger.info(
                "provider_page_fetch_succeeded syncRunId={} targetType={} watchedAddressId={} events={} hasMore={} nextCursorPresent={}",
                claim.run.id,
                claim.run.targetType,
                watchedAddress.id,
                page.events.size,
                page.hasMore,
                page.nextCursor != null,
            )
            page
        } catch (exception: RuntimeException) {
            metrics.recordProviderPage(
                targetType = claim.run.targetType,
                result = if (exception is ProviderDataInvalidException) "MALFORMED" else "FAILED",
            )
            metrics.recordProviderFetchFailure(targetType = claim.run.targetType, sample = sample)
            logger.warn(
                "provider_page_fetch_failed syncRunId={} targetType={} targetId={} watchedAddressId={} chainId={} address={} asset={} error={}",
                claim.run.id,
                claim.run.targetType,
                claim.run.targetId,
                watchedAddress.id,
                watchedAddress.chainId,
                watchedAddress.address,
                watchedAddress.asset,
                exception.conciseMessage(),
            )
            throw exception
        }
    }

    private fun fetchProviderPageWithTimeout(request: ChainProviderEventsPageRequest): ChainProviderEventsPage {
        val providerTimeout = syncProperties.providerTimeout
        val mdcContext = MDC.getCopyOfContextMap()
        val future = try {
            syncProviderExecutor.submit(
                Callable {
                    withMdcContext(mdcContext) {
                        chainProviderPort.fetchObservedEventsPage(request)
                    }
                },
            )
        } catch (exception: RejectedExecutionException) {
            throw SyncCapacityExceededException(syncProperties.providerMaxThreads)
        }

        return try {
            future.get(providerTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            future.cancel(true)
            throw ChainProviderUnavailableException("Provider timeout after $providerTimeout.", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw ChainProviderUnavailableException("Provider fetch interrupted.", exception)
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            when (cause) {
                is RuntimeException -> throw cause
                else -> throw ChainProviderUnavailableException(cause.message ?: "Provider fetch failed.", cause)
            }
        }
    }

    private fun ingestWholePage(events: List<ChainProviderObservedEvent>, progress: SyncProgress): Int {
        var changed = 0
        events.forEach { event ->
            progress.eventsSeen += 1
            progress.claimEventsSeen += 1
            val result = try {
                observedEventApplicationService.ingest(event.toIngestCommand())
            } catch (exception: WatchedAddressNotFoundException) {
                throw ProviderDataInvalidException("Provider returned an event for an address that is not watched.", exception)
            } catch (exception: ObservedTransactionConflictException) {
                throw ProviderDataInvalidException("Provider returned an event that conflicts with stored immutable fields.", exception)
            }
            if (result.result == TransitionOutcome.CREATED || result.result == TransitionOutcome.UPDATED) {
                changed += 1
                progress.eventsChanged += 1
                progress.claimEventsChanged += 1
            }
        }
        return changed
    }

    private fun validatePage(
        request: ChainProviderEventsPageRequest,
        page: ChainProviderEventsPage,
        current: SyncCursor,
    ) {
        val pagination = syncProperties.pagination
        if (request.limit !in 1..pagination.pageSize) {
            throw ProviderDataInvalidException("Provider page request limit is outside the configured bounds.")
        }
        if (page.events.size > request.limit || page.events.size > pagination.pageSize) {
            throw ProviderDataInvalidException("Provider returned more events than the requested page limit.")
        }
        if ((page.nextCursor?.length ?: 0) > pagination.maxCursorLength) {
            throw ProviderDataInvalidException("Provider returned a cursor longer than the configured maximum.")
        }
        page.metadata?.let { metadata ->
            val size = objectMapper.writeValueAsBytes(metadata).size
            if (size > pagination.maxCheckpointJsonLength) {
                throw ProviderDataInvalidException("Provider checkpoint metadata exceeded the configured maximum.")
            }
        }
        if (page.latestBlockHeight != null && page.latestBlockHeight < 0) {
            throw ProviderDataInvalidException("Provider returned a negative latest block height.")
        }
        if (page.safeBlockHeight != null && page.safeBlockHeight < 0) {
            throw ProviderDataInvalidException("Provider returned a negative safe block height.")
        }
        if (page.latestBlockHeight != null && page.safeBlockHeight != null && page.safeBlockHeight > page.latestBlockHeight) {
            throw ProviderDataInvalidException("Provider safe block height cannot exceed latest block height.")
        }
        if (page.hasMore && page.nextCursor == null) {
            throw ProviderDataInvalidException("Provider returned hasMore=true without nextCursor.")
        }
        if (page.hasMore && page.nextCursor == request.cursor) {
            throw ProviderDataInvalidException("Provider returned hasMore=true without cursor progress.")
        }
        if (!page.hasMore && page.nextCursor == null && !hasDurableResumeProgressAfterPage(current = current, page = page)) {
            throw ProviderDataInvalidException("Provider returned a final page without a durable resume cursor or high-water checkpoint.")
        }

        val expected = ChainIdentityNormalizer.normalize(
            chainId = request.chainId,
            address = request.address,
            asset = request.asset,
        )
        var previous: ChainProviderObservedEvent? = null
        page.events.forEach { event ->
            val actual = ChainIdentityNormalizer.normalize(
                chainId = event.chainId,
                address = event.address,
                asset = event.asset,
            )
            if (actual != expected) {
                throw ProviderDataInvalidException("Provider returned an event for the wrong watched address.")
            }
            if (event.eventIndex < 0 || event.blockHeight < 0 || event.confirmations < 0) {
                throw ProviderDataInvalidException("Provider returned an event with negative block metadata.")
            }
            val previousEvent = previous
            if (previousEvent != null && compareEvents(previousEvent, event) > 0) {
                throw ProviderDataInvalidException("Provider returned events out of checkpoint order.")
            }
            previous = event
        }

        page.events.firstOrNull()?.let { first ->
            val currentBlock = current.lastProcessedBlockHeight
            val currentEventIndex = current.lastProcessedEventIndex
            if (
                currentBlock != null &&
                (
                    first.blockHeight < currentBlock ||
                        (first.blockHeight == currentBlock && currentEventIndex != null && first.eventIndex < currentEventIndex)
                    )
            ) {
                throw ProviderDataInvalidException("Provider returned an event behind the stored checkpoint.")
            }
        }
    }

    private fun hasDurableResumeProgressAfterPage(current: SyncCursor, page: ChainProviderEventsPage): Boolean =
        page.events.isNotEmpty() || hasProviderHighWaterProgress(current = current, page = page)

    private fun hasProviderHighWaterProgress(current: SyncCursor, page: ChainProviderEventsPage): Boolean {
        val pageHighWater = pageDurableBlockHighWater(page) ?: return false
        val currentHighWater = current.lastFinalizedBlockHeight
        return currentHighWater == null || pageHighWater > currentHighWater
    }

    private fun resolveDurableHighWater(current: SyncCursor, page: ChainProviderEventsPage): DurableHighWater {
        var blockHeight = current.lastProcessedBlockHeight
        var eventIndex = current.lastProcessedEventIndex
        page.events.forEach { event ->
            if (
                blockHeight == null ||
                event.blockHeight > blockHeight!! ||
                (event.blockHeight == blockHeight && (eventIndex == null || event.eventIndex > eventIndex!!))
            ) {
                blockHeight = event.blockHeight
                eventIndex = event.eventIndex
            }
        }
        val finalizedBlockHeight = maxNullable(current.lastFinalizedBlockHeight, pageDurableBlockHighWater(page))
        return DurableHighWater(
            lastProcessedBlockHeight = blockHeight,
            lastProcessedEventIndex = eventIndex,
            lastFinalizedBlockHeight = finalizedBlockHeight,
        )
    }

    private fun pageDurableBlockHighWater(page: ChainProviderEventsPage): Long? =
        page.safeBlockHeight ?: page.latestBlockHeight

    private fun resolveDurableProviderCursor(page: ChainProviderEventsPage): String? =
        page.nextCursor

    private fun compareEvents(left: ChainProviderObservedEvent, right: ChainProviderObservedEvent): Int =
        compareValuesBy(
            left,
            right,
            ChainProviderObservedEvent::blockHeight,
            ChainProviderObservedEvent::eventIndex,
            ChainProviderObservedEvent::txHash,
        )

    private fun maxNullable(left: Long?, right: Long?): Long? =
        when {
            left == null -> right
            right == null -> left
            else -> maxOf(left, right)
        }

    private fun accountTraversalCheckpoint(
        accountNextOffset: Int,
        skippedBusyLeases: Int,
        accountWrapped: Boolean,
    ): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            put(ACCOUNT_NEXT_OFFSET_FIELD, accountNextOffset)
            put(ACCOUNT_SKIPPED_BUSY_FIELD, skippedBusyLeases)
            put(ACCOUNT_WRAPPED_FIELD, accountWrapped)
        }

    private fun extendCursorLeaseOrThrow(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
    ) {
        val leaseExtendNow = Instant.now(clock)
        val leaseExtended = syncCursorRepository.extendCursorLease(
            watchedAddressId = watchedAddressId,
            lockedBy = lockedBy,
            lockToken = lockToken,
            leaseUntil = leaseExtendNow.plus(syncProperties.pagination.cursorLeaseDuration),
            updatedAt = leaseExtendNow,
        )
        if (!leaseExtended) {
            metrics.recordCursorLease("LOST")
            throw CursorCheckpointAdvanceStaleException(watchedAddressId)
        }
        metrics.recordCursorLease("EXTENDED")
    }

    private fun startCursorLeaseHeartbeat(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
    ): CursorLeaseHeartbeat {
        val failure = AtomicReference<RuntimeException?>()
        val intervalMillis = syncProperties.pagination.cursorHeartbeatInterval.toMillis()
        val future = syncHeartbeatScheduler.scheduleAtFixedRate(
            {
                if (failure.get() != null) {
                    return@scheduleAtFixedRate
                }
                try {
                    extendCursorLeaseOrThrow(
                        watchedAddressId = watchedAddressId,
                        lockedBy = lockedBy,
                        lockToken = lockToken,
                    )
                } catch (exception: RuntimeException) {
                    failure.compareAndSet(null, exception)
                    logger.warn(
                        "cursor_lease_heartbeat_lost watchedAddressId={} lockedBy={} error={}",
                        watchedAddressId,
                        lockedBy,
                        exception.conciseMessage(),
                    )
                }
            },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return CursorLeaseHeartbeat(future = future, failure = failure)
    }

    private fun startHeartbeat(claim: ClaimedSyncRun): ScheduledFuture<*> {
        val intervalMillis = syncProperties.worker.heartbeatInterval.toMillis()
        return syncHeartbeatScheduler.scheduleAtFixedRate(
            {
                try {
                    val marked = syncRunLifecycleService.heartbeat(claim)
                    if (!marked) {
                        logger.debug(
                            "sync_run_heartbeat_stale syncRunId={} workerId={} attempts={} lockToken={}",
                            claim.run.id,
                            claim.lockedBy,
                            claim.attempts,
                            claim.lockToken,
                        )
                    }
                } catch (exception: Exception) {
                    logger.warn(
                        "sync_run_heartbeat_failed syncRunId={} workerId={} attempts={} error={}",
                        claim.run.id,
                        claim.lockedBy,
                        claim.attempts,
                        exception.conciseMessage(),
                    )
                }
            },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun handleClaimFailure(claim: ClaimedSyncRun, progress: SyncProgress, throwable: Throwable) {
        val error = throwable.conciseMessage().take(syncProperties.worker.maxErrorLength)
        val terminal = isTerminalFailure(throwable)
        try {
            if (terminal) {
                syncRunLifecycleService.markFailed(
                    claim = claim,
                    eventsSeen = progress.eventsSeen,
                    eventsChanged = progress.eventsChanged,
                    lastError = error,
                )
            } else {
                syncRunLifecycleService.requeueFailure(
                    claim = claim,
                    eventsSeen = progress.eventsSeen,
                    eventsChanged = progress.eventsChanged,
                    lastError = error,
                    retryAfter = (throwable as? ChainProviderUnavailableException)?.retryAfter,
                )
            }
        } catch (markException: Throwable) {
            throwable.addSuppressed(markException)
            logger.error(
                "sync_run_failure_update_failed syncRunId={} targetType={} targetId={} terminal={} originalError={} markError={}",
                claim.run.id,
                claim.run.targetType,
                claim.run.targetId,
                terminal,
                error,
                markException.conciseMessage(),
            )
            return
        }

        logger.warn(
            "sync_run_execution_failed syncRunId={} targetType={} targetId={} attempts={} failureAttempts={} terminal={} error={}",
            claim.run.id,
            claim.run.targetType,
            claim.run.targetId,
            claim.attempts,
            claim.run.failureAttempts,
            terminal,
            error,
        )
    }

    private fun isTerminalFailure(throwable: Throwable): Boolean =
        when (throwable) {
            is AccountNotFoundException,
            is WatchedAddressByIdNotFoundException,
            is AccountSyncTooLargeException,
            is ProviderDataInvalidException,
            is ProviderDataMismatchException,
            is DataIntegrityViolationException,
            -> true
            is ChainProviderUnavailableException,
            is SyncCapacityExceededException,
            is CursorCheckpointAdvanceStaleException,
            is SyncRunClaimLostException,
            -> false
            is DataAccessException -> false
            else -> false
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
        var claimEventsSeen: Int = 0,
        var claimEventsChanged: Int = 0,
        var accountPagesThisClaim: Int = 0,
        var addressesVisitedThisClaim: Int = 0,
    )

    private data class RunBudget(
        val startedAt: Instant,
        val pagination: SyncProperties.Pagination,
        val accountScoped: Boolean,
    ) {
        fun durationExceeded(now: Instant): Boolean =
            !now.isBefore(startedAt.plus(pagination.maxRunDuration))

        fun accountBudgetExceeded(progress: SyncProgress): Boolean =
            accountScoped &&
                (
                    progress.accountPagesThisClaim >= pagination.maxPagesPerAccountRun ||
                        progress.claimEventsSeen >= pagination.maxEventsPerAccountRun
                    )
    }

    private data class DurableHighWater(
        val lastProcessedBlockHeight: Long?,
        val lastProcessedEventIndex: Int?,
        val lastFinalizedBlockHeight: Long?,
    )

    private class CursorLeaseHeartbeat(
        private val future: ScheduledFuture<*>,
        private val failure: AtomicReference<RuntimeException?>,
    ) {
        fun throwIfFailed() {
            failure.get()?.let { throw it }
        }

        fun cancel() {
            future.cancel(false)
        }
    }

    private sealed interface SyncClaimOutcome {
        data object Done : SyncClaimOutcome

        data class Continuation(
            val reason: SyncRunRequeueReason,
            val runCheckpoint: ObjectNode,
            val delay: Duration,
        ) : SyncClaimOutcome
    }

    private enum class AddressSyncOutcome {
        Done,
        LeaseBusy,
        Continuation,
    }

    private companion object {
        const val ACCOUNT_NEXT_OFFSET_FIELD = "accountNextOffset"
        const val ACCOUNT_SKIPPED_BUSY_FIELD = "accountSkippedBusy"
        const val ACCOUNT_WRAPPED_FIELD = "accountWrapped"
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

class CursorCheckpointAdvanceStaleException(
    val watchedAddressId: UUID,
) : RuntimeException("Cursor checkpoint could not be advanced because the lease or version is stale.")

class SyncRunClaimLostException(
    val syncRunId: UUID,
) : RuntimeException("Sync run claim was lost before completion.")
