package com.example.assetsync.application.sync

import com.example.assetsync.application.account.AccountNotFoundException
import com.example.assetsync.application.account.AccountRepository
import com.example.assetsync.application.account.UnsupportedChainException
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressRepository
import com.example.assetsync.application.isDatabaseFailure
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import com.example.assetsync.application.transaction.ObservedEventApplicationService
import com.example.assetsync.application.transaction.ObservedTransactionConflictException
import com.example.assetsync.application.transaction.WatchedAddressNotFoundException
import com.example.assetsync.config.SyncHeartbeatScheduler
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.domain.model.TransitionOutcome
import com.example.assetsync.domain.policy.AmountPolicy
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import com.fasterxml.jackson.databind.ObjectMapper
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
        val address = watchedAddressRepository.findActiveById(addressId)
            ?: throw WatchedAddressByIdNotFoundException(addressId)
        // Like a disabled address: an address whose chain is disabled is not synced.
        watchedAddressRepository.findSyncableById(addressId)
            ?: throw UnsupportedChainException(address.chainId)
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
                is SyncClaimOutcome.Failed -> {
                    syncRunLifecycleService.markFailed(
                        claim = claim,
                        eventsSeen = progress.eventsSeen,
                        eventsChanged = progress.eventsChanged,
                        lastError = outcome.lastError,
                    )
                    logger.warn(
                        "sync_run_completed_with_failed_addresses syncRunId={} targetType={} targetId={} error={}",
                        claim.run.id,
                        claim.run.targetType,
                        claim.run.targetId,
                        outcome.lastError,
                    )
                }
            }
        } catch (throwable: Throwable) {
            // Thread.interrupted() also clears the flag on purpose: HikariCP refuses to lend a
            // connection to an interrupted thread, and the requeue below has to reach the database.
            val interrupted = Thread.interrupted() || throwable.causedByInterruption()
            if (interrupted) {
                handleInterruptedClaim(claim = claim, progress = progress, throwable = throwable)
                Thread.currentThread().interrupt()
            } else {
                handleClaimFailure(claim = claim, progress = progress, throwable = throwable)
            }
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
            AddressSyncOutcome.ProviderBusy -> SyncClaimOutcome.Continuation(
                reason = SyncRunRequeueReason.PROVIDER_BUSY,
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

    /**
     * One claim of an account sync. The pass over the account's active addresses resumes from the
     * keyset stored in the run checkpoint, so a run whose addresses do not fit one claim completes
     * over several claims instead of starting over each time. An address whose cursor lease is
     * busy is deferred to a revisit after the scan, an address with pages left keeps the scan in
     * place until it is drained, and an address that fails terminally is recorded and skipped, so
     * one broken address neither blocks the others nor the account.
     */
    private fun executeAccount(
        claim: ClaimedSyncRun,
        progress: SyncProgress,
        runBudget: RunBudget,
    ): SyncClaimOutcome {
        val accountId = claim.run.targetId
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }

        val activeAddressCount = watchedAddressRepository.countSyncableByAccountId(accountId)
        if (activeAddressCount > syncProperties.maxAccountSyncAddresses) {
            throw AccountSyncTooLargeException(
                accountId = accountId,
                maxAddresses = syncProperties.maxAccountSyncAddresses,
            )
        }

        val pass = AccountSyncPass.from(claim.run.runCheckpoint)
        while (!pass.scanComplete) {
            val batch = watchedAddressRepository.findSyncableByAccountIdAfter(
                accountId = accountId,
                afterCreatedAt = pass.scanAfterCreatedAt,
                afterId = pass.scanAfterId,
                limit = syncProperties.accountSyncBatchSize,
            )
            if (batch.isEmpty()) {
                pass.scanComplete = true
                break
            }
            for (watchedAddress in batch) {
                if (claimBudgetExceeded(progress, runBudget)) {
                    return accountContinuation(pass, SyncRunRequeueReason.CONTINUATION)
                }
                when (val outcome = processAccountAddress(claim, watchedAddress, progress, runBudget)) {
                    AccountAddressOutcome.Done -> pass.advancePast(watchedAddress)
                    AccountAddressOutcome.LeaseBusy -> {
                        if (!pass.deferBusy(watchedAddress.id)) {
                            // The revisit list is full: wait for this lease instead of skipping it.
                            return accountContinuation(pass, SyncRunRequeueReason.LEASE_BUSY)
                        }
                        pass.advancePast(watchedAddress)
                    }
                    AccountAddressOutcome.Continuation ->
                        return accountContinuation(pass, SyncRunRequeueReason.CONTINUATION)
                    // The pass resumes at this address once the pool has a free thread.
                    AccountAddressOutcome.ProviderBusy ->
                        return accountContinuation(pass, SyncRunRequeueReason.PROVIDER_BUSY)
                    is AccountAddressOutcome.Failed -> {
                        pass.recordFailure(watchedAddress.id, outcome.error)
                        pass.advancePast(watchedAddress)
                    }
                }
            }
        }

        // Each deferred address gets one attempt per claim; the ones still busy wait for the next.
        for (watchedAddressId in pass.pendingRevisits()) {
            if (claimBudgetExceeded(progress, runBudget)) {
                return accountContinuation(pass, SyncRunRequeueReason.CONTINUATION)
            }
            val watchedAddress = watchedAddressRepository.findSyncableById(watchedAddressId)
                ?.takeIf { it.accountId == accountId }
            if (watchedAddress == null) {
                // Disabled, on a chain disabled, or moved since it was deferred: no longer part of this pass.
                pass.revisitDone(watchedAddressId)
                continue
            }
            when (val outcome = processAccountAddress(claim, watchedAddress, progress, runBudget)) {
                AccountAddressOutcome.Done -> pass.revisitDone(watchedAddressId)
                AccountAddressOutcome.LeaseBusy -> Unit
                AccountAddressOutcome.Continuation ->
                    return accountContinuation(pass, SyncRunRequeueReason.CONTINUATION)
                AccountAddressOutcome.ProviderBusy ->
                    return accountContinuation(pass, SyncRunRequeueReason.PROVIDER_BUSY)
                is AccountAddressOutcome.Failed -> {
                    pass.revisitDone(watchedAddressId)
                    pass.recordFailure(watchedAddressId, outcome.error)
                }
            }
        }
        if (pass.pendingRevisits().isNotEmpty()) {
            return accountContinuation(pass, SyncRunRequeueReason.LEASE_BUSY)
        }

        return if (pass.hasFailures) SyncClaimOutcome.Failed(pass.failureSummary()) else SyncClaimOutcome.Done
    }

    private fun claimBudgetExceeded(progress: SyncProgress, runBudget: RunBudget): Boolean =
        runBudget.accountBudgetExceeded(progress) || runBudget.durationExceeded(Instant.now(clock))

    private fun accountContinuation(pass: AccountSyncPass, reason: SyncRunRequeueReason): SyncClaimOutcome =
        SyncClaimOutcome.Continuation(
            reason = reason,
            runCheckpoint = pass.toCheckpoint(),
            delay = if (reason == SyncRunRequeueReason.LEASE_BUSY || reason == SyncRunRequeueReason.PROVIDER_BUSY) {
                syncProperties.pagination.cursorLeaseRetryDelay
            } else {
                syncProperties.pagination.continuationRequeueDelay
            },
        )

    /**
     * Syncs one address of an account run. Failures that would fail the same way on every attempt
     * for this address only (malformed or rejected provider data, a configuration gap of the
     * address, a database constraint) end that address. A configuration failure of the whole
     * provider, such as a rejected credential or a redirect, would fail every address the same way,
     * so it fails the run at once, like anything retryable, a lost claim or lease, and shutdown
     * interrupts, which keep failing the whole claim.
     */
    private fun processAccountAddress(
        claim: ClaimedSyncRun,
        watchedAddress: WatchedAddress,
        progress: SyncProgress,
        runBudget: RunBudget,
    ): AccountAddressOutcome =
        try {
            when (processAddressWithinClaim(claim = claim, watchedAddress = watchedAddress, progress = progress, runBudget = runBudget)) {
                AddressSyncOutcome.Done -> AccountAddressOutcome.Done
                AddressSyncOutcome.LeaseBusy -> AccountAddressOutcome.LeaseBusy
                AddressSyncOutcome.Continuation -> AccountAddressOutcome.Continuation
                AddressSyncOutcome.ProviderBusy -> AccountAddressOutcome.ProviderBusy
            }
        } catch (exception: RuntimeException) {
            val addressTerminal = exception is ProviderDataInvalidException ||
                exception is AddressConfigurationException ||
                exception is DataIntegrityViolationException
            if (!addressTerminal || Thread.currentThread().isInterrupted) {
                throw exception
            }
            val error = exception.runError()
            logger.warn(
                "account_sync_address_failed syncRunId={} accountId={} watchedAddressId={} error={}",
                claim.run.id,
                watchedAddress.accountId,
                watchedAddress.id,
                error,
            )
            logFailureDetail(claim, error, exception)
            AccountAddressOutcome.Failed(error)
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
                    fromEventIndex = current.lastProcessedEventIndex,
                    safeBlockHeight = current.lastFinalizedBlockHeight,
                    checkpoint = current.checkpoint,
                )

                val page = try {
                    fetchProviderPage(
                        claim = claim,
                        watchedAddress = watchedAddress,
                        request = request,
                        current = current,
                    )
                } catch (exception: SyncCapacityExceededException) {
                    // The service's own pool is full, which says nothing about the provider: the run
                    // comes back as a continuation, spends no retry budget, and stays bounded by
                    // max-continuations-per-run.
                    return AddressSyncOutcome.ProviderBusy
                }

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
                val providerCursor = resolveDurableProviderCursor(current = current, page = page)
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
            releaseCursorLeaseQuietly(claim = claim, watchedAddressId = watchedAddress.id, lockToken = cursorLockToken)
        }
    }

    /**
     * Releases the cursor lease without letting the release change the outcome of the page loop:
     * the interrupt flag is parked so HikariCP still lends a connection during shutdown, and a
     * failed release is logged rather than thrown because the lease expires and recovery clears it.
     */
    private fun releaseCursorLeaseQuietly(claim: ClaimedSyncRun, watchedAddressId: UUID, lockToken: UUID) {
        val wasInterrupted = Thread.interrupted()
        try {
            val released = syncCursorRepository.releaseCursorLeaseFenced(
                watchedAddressId = watchedAddressId,
                lockedBy = claim.lockedBy,
                lockToken = lockToken,
                updatedAt = Instant.now(clock),
            )
            metrics.recordCursorLease(if (released) "RELEASED" else "LOST")
            logger.debug(
                "cursor_lease_released syncRunId={} targetType={} watchedAddressId={} released={}",
                claim.run.id,
                claim.run.targetType,
                watchedAddressId,
                released,
            )
        } catch (exception: RuntimeException) {
            metrics.recordCursorLease("RELEASE_FAILED")
            logger.warn(
                "cursor_lease_release_failed syncRunId={} targetType={} watchedAddressId={} error={}",
                claim.run.id,
                claim.run.targetType,
                watchedAddressId,
                exception.conciseMessage(),
            )
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt()
            }
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
            // The failures mapped below are deterministic for this event, so they are terminal
            // instead of burning retries: the same event would fail the same way on every attempt.
            val result = try {
                observedEventApplicationService.ingest(event.toIngestCommand(source = "provider:${chainProviderPort.providerName}"))
            } catch (exception: WatchedAddressNotFoundException) {
                throw ProviderDataInvalidException("Provider returned an event for an address that is not watched.", exception)
            } catch (exception: ObservedTransactionConflictException) {
                // The natural key has no direction: a transfer of the address to itself sent as two
                // rows on either side of a page boundary ends here, after the first row was stored.
                val fields = exception.conflictingFields.map { it.name.lowercase() }.sorted().joinToString(" and ")
                val txHash = ChainIdentityNormalizer.normalizeTxHash(event.chainId, event.txHash)
                throw ProviderDataInvalidException(
                    "Provider returned transaction $txHash event ${event.eventIndex} with another $fields than the stored row; " +
                        "one row per event, and a transfer of the address to itself is left out.",
                    exception,
                )
            } catch (exception: InvalidObservedEventRequestException) {
                throw ProviderDataInvalidException("Provider returned an invalid event: ${exception.message}", exception)
            } catch (exception: IllegalArgumentException) {
                throw ProviderDataInvalidException("Provider returned an event that breaks a domain invariant: ${exception.message}", exception)
            } catch (exception: UnsupportedChainException) {
                throw AddressConfigurationException("Chain ${exception.chainId} is not enabled, so its events cannot be ingested.", exception)
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
        // A final page may omit its cursor: the stored one or the checkpoint resumes the next sync
        // (resolveDurableProviderCursor), and stored heights never go backwards.

        val expected = ChainIdentityNormalizer.normalize(
            chainId = request.chainId,
            address = request.address,
            asset = request.asset,
        )
        var previous: ChainProviderObservedEvent? = null
        val eventsByKey = HashMap<Pair<String, Int>, ChainProviderObservedEvent>()
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
            // Checked for every event before the first one is written, so a bad event later in
            // the page cannot leave the events before it committed behind a terminal failure.
            val txHash = ChainIdentityNormalizer.normalizeTxHash(expected.chainId, event.txHash)
            ChainIdentityNormalizer.txHashViolation(chainId = expected.chainId, txHash = txHash)?.let { violation ->
                throw ProviderDataInvalidException("Provider returned an event with a malformed transaction hash: $violation")
            }
            // One row per event. An exact repeat is harmless, ingest is idempotent; a repeat with
            // another direction or amount, such as a transfer of the address to itself sent as
            // INBOUND and OUTBOUND, would conflict with the row written just before it.
            val sameKey = eventsByKey.putIfAbsent(txHash to event.eventIndex, event)
            if (sameKey != null && (sameKey.direction != event.direction || sameKey.amount.compareTo(event.amount) != 0)) {
                throw ProviderDataInvalidException(
                    "Provider returned transaction $txHash event ${event.eventIndex} twice with another direction or amount; " +
                        "one row per event, and a transfer of the address to itself is left out.",
                )
            }
            if (AmountPolicy.normalizedOrNull(event.amount) == null) {
                throw ProviderDataInvalidException("Provider returned an amount that is negative or does not fit numeric(38,18).")
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

    private fun resolveDurableHighWater(current: SyncCursor, page: ChainProviderEventsPage): DurableHighWater {
        var blockHeight = current.lastProcessedBlockHeight
        var eventIndex = current.lastProcessedEventIndex
        page.events.forEach { event ->
            if (
                blockHeight == null ||
                event.blockHeight > blockHeight ||
                (event.blockHeight == blockHeight && (eventIndex == null || event.eventIndex > eventIndex))
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

    /**
     * A final page may omit its cursor. After an empty final page the stored cursor still points
     * right after the last processed event, so it is kept for bridges that resume only by cursor.
     * After a final page with events it is cleared: replaying from it would return those events,
     * which now sit behind the checkpoint. Every request also carries the checkpoint's block and
     * event index, so a bridge without a cursor resumes from there instead of from the start.
     */
    private fun resolveDurableProviderCursor(current: SyncCursor, page: ChainProviderEventsPage): String? =
        page.nextCursor ?: current.providerCursor.takeIf { page.events.isEmpty() }

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

    /**
     * A worker thread is interrupted only while the process shuts down, so the run goes back to
     * the queue through the budget-neutral requeue path instead of counting as a provider failure.
     * Already committed page events stay valid; the next claim resumes from the durable checkpoint.
     */
    private fun handleInterruptedClaim(claim: ClaimedSyncRun, progress: SyncProgress, throwable: Throwable) {
        val requeued = try {
            syncRunLifecycleService.requeue(
                claim = claim,
                eventsSeen = progress.eventsSeen,
                eventsChanged = progress.eventsChanged,
                lastError = INTERRUPTED_REQUEUE_ERROR,
            )
        } catch (markException: Throwable) {
            throwable.addSuppressed(markException)
            logger.error(
                "sync_run_interrupt_requeue_failed syncRunId={} targetType={} targetId={} originalError={} markError={}",
                claim.run.id,
                claim.run.targetType,
                claim.run.targetId,
                throwable.conciseMessage(),
                markException.conciseMessage(),
            )
            return
        }
        logger.warn(
            "sync_run_requeued_on_interrupt syncRunId={} targetType={} targetId={} attempts={} requeued={} cause={}",
            claim.run.id,
            claim.run.targetType,
            claim.run.targetId,
            claim.attempts,
            requeued,
            throwable.conciseMessage(),
        )
    }

    private fun Throwable.causedByInterruption(): Boolean =
        generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
            .any { it is InterruptedException }

    private fun handleClaimFailure(claim: ClaimedSyncRun, progress: SyncProgress, throwable: Throwable) {
        val error = throwable.runError().take(syncProperties.worker.maxErrorLength)
        logFailureDetail(claim, error, throwable)
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
            is ProviderConfigurationException,
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

    /**
     * The `last_error` a sync run shows to API readers. The service's own exceptions carry messages
     * written for that purpose; a database or unexpected failure is reduced to its class, because
     * its message can quote SQL and bound values. The full detail goes to the log instead.
     */
    private fun Throwable.runError(): String =
        if (isDatabaseFailure()) {
            "Database error (${javaClass.simpleName})."
        } else {
            when (this) {
                is ChainProviderUnavailableException,
                is ProviderDataInvalidException,
                is ProviderConfigurationException,
                is AccountNotFoundException,
                is WatchedAddressByIdNotFoundException,
                is AccountSyncTooLargeException,
                is SyncCapacityExceededException,
                is CursorCheckpointAdvanceStaleException,
                is SyncRunClaimLostException,
                -> conciseMessage()
                else -> "Unexpected error (${javaClass.simpleName})."
            }
        }

    private fun logFailureDetail(claim: ClaimedSyncRun, error: String, throwable: Throwable) {
        if (error != throwable.conciseMessage()) {
            logger.warn("sync_run_failure_detail syncRunId={} error={}", claim.run.id, error, throwable)
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

        /** An account pass that finished with addresses that failed terminally. */
        data class Failed(val lastError: String) : SyncClaimOutcome
    }

    private enum class AddressSyncOutcome {
        Done,
        LeaseBusy,
        Continuation,
        ProviderBusy,
    }

    private sealed interface AccountAddressOutcome {
        data object Done : AccountAddressOutcome

        data object LeaseBusy : AccountAddressOutcome

        data object Continuation : AccountAddressOutcome

        data object ProviderBusy : AccountAddressOutcome

        data class Failed(val error: String) : AccountAddressOutcome
    }

    private companion object {
        const val INTERRUPTED_REQUEUE_ERROR = "worker interrupted during shutdown; requeued without consuming retry budget"
    }
}

class AccountSyncTooLargeException(
    val accountId: UUID,
    val maxAddresses: Int,
) : RuntimeException("Account has more than $maxAddresses active watched addresses.")

class SyncCapacityExceededException(
    val maxConcurrentSyncs: Int,
) : RuntimeException("Sync capacity exceeded: at most $maxConcurrentSyncs concurrent provider fetches.")

class CursorCheckpointAdvanceStaleException(
    val watchedAddressId: UUID,
) : RuntimeException("Cursor checkpoint could not be advanced because the lease or version is stale.")

class SyncRunClaimLostException(
    val syncRunId: UUID,
) : RuntimeException("Sync run claim was lost before completion.")
