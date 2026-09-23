# Failure Modes Specification

Status: Implemented behavior of the current MVP  
Scope: MVP reliability behavior  
Source of truth: `docs/architecture.md`

## 1. Reliability Principles

The MVP is designed around PostgreSQL constraints, explicit transactions, row locks, and a transactional outbox. External delivery is at-least-once. Provider calls are not made while holding database transactions. The MVP outbox publisher uses a bounded local adapter while due rows are claimed with `FOR UPDATE SKIP LOCKED`.

Common response principles:

- Never report fake success when PostgreSQL is unavailable.
- Keep already committed observed events after a later provider failure.
- Treat duplicate input as expected, not exceptional.
- Reject immutable conflicts instead of silently rewriting history.
- Preserve enough diagnostic data in `sync_runs`, logs, and outbox rows to troubleshoot failures.

## 2. Duplicate Observed Events

Scenario:

- The API, active chain provider, or future external delivery sends the same observed event more than once.

Expected behavior:

- The natural key `chainId + txHash + eventIndex + address + asset` identifies the stored transaction.
- The existing row is loaded `FOR UPDATE`.
- If immutable fields and lifecycle state match, the state machine returns `NoChange`.
- The API returns `200 OK` with `result = NO_CHANGE`.
- No additional outbox event is created.

Operational signal:

- Increment duplicate observed-event metrics when metrics are implemented.
- Log at debug or info level with natural key fields.

## 3. Provider Stale Confirmations

Scenario:

- The provider sends a lower confirmation count than the stored count.
- The provider sends `SEEN` after the transaction has already reached `CONFIRMED`.

Expected behavior:

- Stored confirmations do not decrease.
- Stored status does not move from `CONFIRMED` back to `SEEN`.
- The event is treated as stale `NoChange`.
- No outbox event is created.

Operational signal:

- Log stale confirmation diagnostics with stored and incoming counts.

## 4. Immutable Conflicts

Scenario:

- An incoming event matches the natural key but has a different immutable value, such as `amount` or `direction`.

Expected behavior:

- Reject the event with domain `Conflict`.
- API maps the conflict to `409 Conflict`.
- Do not update the observed transaction.
- Do not create an outbox event.

Problem type:

```text
https://asset-sync-service/errors/immutable-field-conflict
```

Operational signal:

- Log structured diagnostics including `chainId`, `txHash`, `eventIndex`, `address`, and `asset`.
- Treat repeated conflicts as provider or upstream data quality issues.

## 5. Reorg After Confirmed

Scenario:

- A transaction already stored as `CONFIRMED` is later reported as `REVERTED`.

Expected behavior:

- Transition `CONFIRMED -> REVERTED`.
- Set `revertedAt`.
- Preserve `confirmedAt` for lifecycle history.
- Create one `TRANSACTION_REVERTED` outbox event in the same database transaction.
- Duplicate reorg events become `NoChange`.

Operational signal:

- Increment reverted transaction metrics when metrics are implemented.
- Log the transition with transaction id and natural key.

## 6. PostgreSQL Unavailable

Scenario:

- PostgreSQL connection cannot be acquired.
- Query execution fails due to database outage.

Expected behavior:

- API returns `503 Service Unavailable`.
- Readiness health check fails.
- No fake success response is returned.
- No provider call should be started for a sync request if the initial `sync_run` cannot be created.

Operational signal:

- Log database exception class and operation name.
- Avoid logging credentials or raw connection strings.

## 7. Provider Timeout Or Backpressure

Scenario:

- The active chain provider times out, returns 429/5xx, or is unavailable during a sync page fetch.

Expected behavior:

- Sync POST has already returned `202 Accepted`; provider failure is observed through `GET /api/v1/sync-runs/{id}`.
- Provider call is outside a database transaction and outside the request thread.
- `asset-sync.sync.provider-timeout` is the deadline for one provider page fetch.
- HTTP provider responses are bounded by `asset-sync.sync.pagination.max-provider-page-bytes` before JSON parsing.
- Retryable provider failures requeue the current `sync_run` as `QUEUED` with bounded backoff.
- HTTP 429 is retryable provider backpressure, increments `failure_attempts`, and uses valid `Retry-After` values capped by the configured max backoff.
- At max attempts, the current `sync_run` is marked `FAILED` in a short fenced transaction.
- Events already committed before the timeout remain valid.
- The cursor checkpoint is not advanced for the failed page.
- No long-lived database locks are held while waiting for provider response.
- Under the Alchemy provider, a repeated `pageKey`, a restarted continuation page, a row outside the requested block window, a safe frontier above the latest block, and an exhausted per-fetch RPC or time budget before the first block was drained are all retryable: the cursor stays on its block boundary and the next attempt rescans from it. A budget exhausted after at least one drained block returns that prefix instead.
- The local token bucket waits for a token only within the fetch deadline; a wait that cannot be met is a retryable outage rather than a provider 429.
- These availability failures, and for Alchemy rejected credentials or configuration, turn the provider health indicator `DOWN` until the next successful fetch. A data error for one address (a `4xx`, malformed JSON, an oversized body, an unmappable Alchemy row) keeps the indicator's state and appears as its `lastDataError` detail.

Operational signal:

- Store concise failure detail in `sync_runs.last_error`.
- Log `syncRunId`, target type, target id, and provider operation.

## 8. Malformed Provider Page

Scenario:

- The HTTP provider omits required `events` or `hasMore`.
- The provider returns too many events, an oversized cursor/body/checkpoint, `hasMore=true` without cursor progress, wrong address/asset, invalid high-water fields, or insufficient final resume state. A final empty page may omit `nextCursor` only when it supplies durable block high-water such as `safeBlockHeight` or `latestBlockHeight`.
- The provider returns events out of non-decreasing `(blockHeight, eventIndex, txHash)` order, or a page whose first event is behind the stored `last_processed_block_height` / `last_processed_event_index` checkpoint.
- An event carries an amount that is negative or does not fit `numeric(38, 18)`, which PostgreSQL would otherwise round or reject, or a transaction hash that is blank or breaks the chain's format rules (`0x` and 64 hex digits on `eth-sepolia` and `eth-mainnet`, no whitespace, `/`, or `:` on `local-evm`, no control characters anywhere). Every event of the page is checked before the first one is written.
- The Alchemy adapter meets a row it cannot map honestly: a `uniqueId` without the ERC-20 `:log:{n}` suffix or not matching the transaction hash, a non-hex `blockNum` or `rawContract.value`, a `rawContract.decimal` that disagrees with the registry, a missing address or contract, a category other than `erc20`, a row on the wrong side of the watched address, or a malformed provider cursor.

Expected behavior:

- Classify the page as terminal provider data invalid.
- Do not ingest any event from a page that fails validation.
- An event that passes page validation but fails ingestion deterministically is terminal as well: an immutable-field conflict, an address that is not watched, a rejected ingest rule, or a broken domain invariant. Events of the page ingested before it stay committed, and the retry budget is not spent on attempts that would fail the same way.
- In an account sync a terminal failure ends only that address. The pass continues with the other addresses and, once complete, marks the run `FAILED` with `<n> of <m> addresses failed terminally: <addressId>: <error>; ...` in `last_error`. An address that keeps failing is taken out of account syncs with `PATCH /api/v1/addresses/{addressId}` and `{"status":"DISABLED"}`.
- Do not advance `sync_cursors`.
- Mark the current sync run `FAILED` with bounded `last_error`.
- Lifecycle updates for events already behind the checkpoint are not delivered through the sync path. They arrive through `POST /api/v1/observed-events`, and provider adapters are expected to emit only finalized events.

Operational signal:

- Log page validation failure with sync run id, watched address id, chain id, asset, event count, and whether `hasMore` was set. Do not log full opaque cursors.

## 9. Cursor Lease Busy Or Stale

Scenario:

- A direct address sync and an account sync target the same watched address.
- A worker loses a cursor lease or attempts to advance with a stale lock token/version or expired `locked_until`.

Expected behavior:

- A busy cursor lease does not mark success. Direct address sync requeues as `LEASE_BUSY`; account sync defers that address, finishes its scan of the other addresses, and revisits the deferred ones once per claim until their leases are free, requeueing as `LEASE_BUSY` while any is still busy.
- A worker must extend its cursor lease before checkpointing and through the cursor heartbeat while long page work is in progress. If extension fails, checkpoint advancement is skipped and the run is retried as a stale-owner failure.
- Busy lease continuations increment `continuation_count`, not `failure_attempts`.
- Stale checkpoint advancement affects zero rows. Already committed page events remain valid and the old checkpoint causes safe idempotent replay.
- Expired cursor leases are cleared by recovery.

Operational signal:

- Log cursor lease acquired, busy, advanced, stale, and released events with watched address id and sync run id.

## 10. Healthy Continuation Limit

Scenario:

- A large address/account hits configured page, event, or run-duration bounds while the provider still has more pages.

Expected behavior:

- The current page is ingested and checkpointed first.
- The sync run is requeued as `CONTINUATION`.
- `continuation_count` increments; `failure_attempts` does not.
- If `max-continuations-per-run` is exceeded, the run is marked `FAILED` as likely stuck or misconfigured.

## 11. Publisher Failure And Retry

Scenario:

- The outbox publisher adapter fails to publish an event.

Expected behavior:

- The outbox event remains durable in PostgreSQL.
- `attempts` is incremented.
- `last_error` is updated with a concise failure summary.
- `status` is set to `FAILED`, or `DEAD` when max attempts has been reached.
- `next_attempt_at` is moved forward using bounded backoff for `FAILED` rows.
- A later poller run retries due `FAILED` rows.
- `DEAD` rows are terminal and excluded from due/backlog counts.

Operational signal:

- Emit outbox failure/dead metrics.
- Keep error messages bounded to avoid unbounded row growth.

## 12. Process Crash Around Outbox Publish

Scenario:

- The process crashes after publishing to the local adapter but before marking the outbox row `PUBLISHED`.
- Or the publish succeeds and the `markPublished` completion update fails.

Expected behavior:

- The row remains `NEW` or `FAILED` with `next_attempt_at` holding the previous processing lease deadline.
- Completion failure after successful publish does not increment publish attempts and must not promote the row to `DEAD`.
- After the lease expires, the poller may publish it again.
- This is acceptable because outbox delivery is at-least-once.
- Downstream consumers must deduplicate by outbox `id` or `idempotency_key`.

Scenario:

- The process crashes before publishing.

Expected behavior:

- The row is retried after the processing lease expires.

## 13. Concurrent Sync For Same Address

Scenario:

- Two clients start sync for the same watched address at the same time.

Expected behavior:

- Duplicate in-flight target lookup happens before the queue cap.
- Both clients receive `202 Accepted` for the same existing `QUEUED` or `RUNNING` `sync_runs` row.
- Exact-target single-flight is enforced by a partial unique index on `target_type, target_id where status in ('QUEUED','RUNNING')`.
- Observed event ingestion remains safe through natural unique keys and row locks.
- Duplicate events return `NoChange`.
- Outbox idempotency keys prevent duplicate lifecycle events.

Operational signal:

- Log each sync run independently.
- Track duplicate processing metrics when implemented.

Notes:

- Duplicate provider work can still happen after process crash or lease recovery because sync execution is at-least-once.
- Advisory locks or a counter table can be added later if operators need stricter global admission guarantees.

## 14. Expired Running Sync Lease

Scenario:

- A worker claims a `QUEUED` sync run, marks it `RUNNING`, then crashes or loses progress before fenced completion.

Expected behavior:

- Recovery finds expired `RUNNING` rows with `locked_until < now()` using `FOR UPDATE SKIP LOCKED`.
- Recovery increments `failure_attempts` because an expired `RUNNING` row means the previous owner failed under lease.
- If the incremented `failure_attempts` is below max attempts, recovery requeues the row as `QUEUED`, sets a future `next_attempt_at`, records concise `last_error`, and clears `locked_by`, `lock_token`, `locked_until`, and `heartbeat_at`.
- If the incremented `failure_attempts` reaches max attempts, recovery marks the row `FAILED`, sets `finished_at`, records concise `last_error`, and clears lock fields.
- Recovery never overwrites `SUCCEEDED` or `FAILED` rows.
- Legacy stale `STARTED` recovery remains separate for rolling deploy compatibility.

Operational signal:

- Log recovered run id, target, attempts, failure attempts, and whether the run was requeued or failed.
- A stale worker completion after recovery affects zero rows because fenced completion requires matching `locked_by`, `lock_token`, and `attempts`.

## 15. Process Crash During Sync Execution

Scenario:

- The process crashes after provider work or partial event ingestion but before sync-run completion.

Expected behavior:

- Already committed observed events and outbox rows remain valid.
- If the crash happens after a page checkpoint advances but before sync-run completion, retry resumes from the advanced cursor.
- If the crash happens before checkpoint advancement, retry replays the previous page and ingestion idempotency handles duplicates.
- The `RUNNING` sync run is recovered after lease expiry and may execute again. The worker claims only `QUEUED` runs, so the recovery job requeues it on its first tick after the lease expires: every minute by default (`asset-sync.sync.recovery.fixed-delay`), which spends one retry attempt. Until then, a new sync request for the same target returns that run.
- Duplicate future provider events are safe because observed-event ingestion is idempotent.
- This is at-least-once sync execution, not exactly-once provider work.

## 16. Graceful Shutdown During Sync Execution

Scenario:

- The process receives a shutdown signal while the worker owns one or more `RUNNING` sync runs.

Expected behavior:

- The worker stops claiming new runs immediately and lets in-flight runs finish for up to `asset-sync.sync.worker.shutdown-timeout`; the web server drains HTTP requests concurrently within `spring.lifecycle.timeout-per-shutdown-phase`.
- Runs that finish inside the window complete normally.
- Runs still in flight afterwards are interrupted. An interrupted run is requeued as `QUEUED` with `last_requeue_reason = FAILURE` and a `last_error` naming the shutdown; `failure_attempts` is not incremented, so restarts never consume retry budget.
- The cursor lease of an interrupted run is released before the run is requeued; if the release fails, the lease expires and recovery clears it.
- Already committed page events remain valid; the next claim resumes from the durable checkpoint.
- The container stop timeout must outlast the shutdown phase. Docker Compose gives the application 40 seconds (`stop_grace_period`); with Docker's default of 10 seconds a run still in flight would be killed before it is requeued, stay `RUNNING` until recovery finds its expired lease, and lose one retry attempt.

Operational signal:

- Log `sync_worker_draining`, `sync_worker_drain_timeout`, `sync_run_requeued_on_interrupt`, and `sync_worker_stopped` with the worker id.

## 17. Provider Configuration Failure

Scenario:

- The configured provider rejects the credentials (HTTP 401/403 or a JSON-RPC `-32600` envelope), an enabled chain has no provider network mapping, active watched addresses lack an enabled asset config, `start-mode=configured-block` has no start block for the chain, one block holds more events for the watched address than `asset-sync.sync.pagination.page-size`, which the page contract cannot split, or the chain of a synced event was disabled after its addresses were registered.

Expected behavior:

- At startup with `asset-sync.provider.type=alchemy`, the preflight fails the process before the sync worker starts: static validation (key, auth mode, templates, numeric caps, `max-rpc-calls-per-fetch >= 4`), the registry rules, and one `eth_blockNumber` probe per required network. The failure is a `ProviderConfigurationException` whose message names the chains or `(chain_id, asset)` pairs and the operator action, never the key or the endpoint.
- During a sync run, `ProviderConfigurationException` is terminal: the run is marked `FAILED` at once, `failure_attempts` is not spent on retries that cannot succeed, and the checkpoint does not move.
- `sync_runs.last_error`, log lines, health details, and exception messages are scrubbed of the API key; transport failures that embed a request URL are rethrown with a bounded scrubbed message and without their cause.

Operational signal:

- Startup log `alchemy_preflight_succeeded` with the probed networks, or the startup failure with the scrubbed message.
- Log `alchemy_rpc_failed` and `alchemy_provider_page_fetch_failed` with the scrubbed error.
- Health component `alchemyChainProvider` with `provider`, `authMode`, `networks`, `state`, and the scrubbed `error`.

## 18. Future Failure Modes

Deferred areas:

- Real provider rate limiting and partial block-range failures.
- Broker-specific delivery errors.
- CDC connector lag.
- Balance projection rebuild failure.
- Multi-tenant authorization failures.
- Distributed scheduler split-brain.

These areas require additional design before implementation.
