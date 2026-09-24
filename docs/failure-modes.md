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

- `asset.sync.observed.events.ingested` counts each duplicate with `result=NO_CHANGE`.
- The `observed_event_ingested` INFO line carries `result=NO_CHANGE` and the natural key fields.

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

- A stale event is counted and logged like any other `NO_CHANGE` (section 2), with the stored status; the stored and incoming confirmation counts are not logged.

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

- `asset.sync.observed.transaction.transitions` counts the transition with `eventType=TRANSACTION_REVERTED`.
- The `observed_transaction_transition` INFO line carries the transaction id, the natural key, and the outbox event id.

## 6. PostgreSQL Unavailable

Scenario:

- PostgreSQL connection cannot be acquired.
- Query execution fails due to database outage, or PostgreSQL refuses writes, for example after a failover to a read-only standby.
- PostgreSQL stops answering without closing connections, for example on a paused host or behind a network partition.

Expected behavior:

- API returns `503 Service Unavailable` with the `database-unavailable` problem type. In the protected profiles this includes a request that carries credentials: HTTP Basic cannot read its user store to check them, so the answer is neither a `401` nor a Basic challenge. A request without credentials keeps its `401`.
- A request waits for a pooled connection up to the Hikari `connection-timeout`, 30 seconds by default.
- A query already sent to a server that stopped answering ends after the 40-second JDBC socket timeout (`socketTimeout`), which sits above the 30-second `statement_timeout`; raise both together. A new connection gives up on the TCP connect after 5 seconds (`connectTimeout`).
- A `COMMIT` is not bound by `statement_timeout`. One that waits longer than the socket timeout, behind a stalled synchronous standby or disk, answers `503` although PostgreSQL may still commit it, so a client that retries can repeat the change: a retried `POST /api/v1/accounts` with the same `externalRef` gets `409`, one without an `externalRef` creates a second account.
- Every request with credentials reads the user store, so during the outage it waits for the connection timeout too, including authenticated `/actuator/metrics` and `/actuator/prometheus` requests; anonymous health probes do not.
- A sync run that meets the outage records `Database error (<class>).` and is retried with backoff.
- Readiness health check fails.
- No fake success response is returned.
- No provider call should be started for a sync request if the initial `sync_run` cannot be created.

Operational signal:

- `database_operation_failed` at ERROR with the request path, the exception class, and the class of its root cause, both from the API and from the credential check. A username the user store cannot hold, such as one with a NUL character, is a bad credential (`401`), not an outage.
- Avoid logging credentials or raw connection strings.

## 7. Provider Timeout Or Backpressure

Scenario:

- The active chain provider times out, returns 429/5xx, or is unavailable during a sync page fetch.

Expected behavior:

- Sync POST has already returned `202 Accepted`; provider failure is observed through `GET /api/v1/sync-runs/{id}`.
- Provider call is outside a database transaction and outside the request thread.
- `asset-sync.sync.provider-timeout` is the deadline for one provider page fetch.
- HTTP provider responses are bounded by `asset-sync.sync.pagination.max-provider-page-bytes` before JSON parsing.
- A response that is still arriving when the deadline cancels the fetch stops being read within one `asset-sync.provider.read-timeout`, and a body over the limit or behind an error status is closed unread, so the provider thread returns to the pool; a trickling response cannot hold it for the length of its body.
- Retryable provider failures requeue the current `sync_run` as `QUEUED` with bounded backoff.
- HTTP 429 is retryable provider backpressure, increments `failure_attempts`, and uses valid `Retry-After` values capped by the configured max backoff.
- A fetch the full provider pool (`asset-sync.sync.provider-max-threads`) cannot take requeues the run with backoff and does not increment `failure_attempts`: the service's own capacity says nothing about the provider.
- At max attempts, the current `sync_run` is marked `FAILED` in a short fenced transaction.
- Events already committed before the timeout remain valid.
- The cursor checkpoint is not advanced for the failed page.
- No long-lived database locks are held while waiting for provider response.
- Under the Alchemy provider, a repeated `pageKey`, a restarted continuation page, a row outside the requested block window, a safe frontier above the latest block, and an exhausted per-fetch RPC or time budget before the first block was drained are all retryable: the cursor stays on its block boundary and the next attempt rescans from it. A budget exhausted after at least one drained block returns that prefix instead.
- The local token bucket waits for a token only within the fetch deadline; a wait that cannot be met is a retryable outage rather than a provider 429.
- An Alchemy that is unavailable when the service starts is the same case: the startup probe leaves the provider in the `probe-failed` state instead of stopping the process, and sync runs against it retry as above.
- These availability failures, and rejected credentials or a redirect from either provider, turn the provider health indicator `DOWN` until the next successful fetch. A data error or a configuration gap of one address (a `4xx`, malformed JSON, an oversized body, an unmappable Alchemy row; under Alchemy also its chain without a network or a start block, its asset without an enabled config, or a block larger than a page) keeps the indicator's state and appears as its `lastDataError` detail until the next successful fetch of any address; the run's `last_error` keeps it.

Operational signal:

- Store concise failure detail in `sync_runs.last_error`. Readers of the run see the service's own messages only; a database or unexpected failure is stored as its class, and the full exception is logged as `sync_run_failure_detail`. A failure to reach the HTTP bridge is stored by its kind, never with the bridge URL, which may carry a bridge token.
- Log `syncRunId`, target type, target id, and provider operation.

## 8. Malformed Provider Page

Scenario:

- The HTTP provider omits required `events` or `hasMore`.
- The provider returns too many events, an oversized cursor/body/checkpoint, a page field string over 100 000 characters, `hasMore=true` without cursor progress, wrong address/asset, or invalid high-water fields. A final page may omit `nextCursor`, even without new events or heights.
- The provider returns one event twice in a page with another direction or amount, such as a transfer of the address to itself sent as two rows.
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
- Duplicate events count as `asset.sync.observed.events.ingested` with `result=NO_CHANGE`; duplicate provider work has no metric of its own.

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

- The configured provider rejects the credentials (HTTP 401/403 or a JSON-RPC `-32600` envelope), an enabled chain with active watched addresses has no provider network mapping, active watched addresses lack an enabled asset config, `start-mode=configured-block` has no start block for the chain, one block holds more events for the watched address than `asset-sync.sync.pagination.page-size`, which the page contract cannot split, or the chain of a synced event was disabled after its addresses were registered.

Expected behavior:

- At startup with `asset-sync.provider.type=alchemy`, the preflight fails the process before the sync worker starts: static validation (key, auth mode, templates, numeric caps, `max-rpc-calls-per-fetch >= 4`), the registry rules, and one `eth_blockNumber` probe per required network that Alchemy rejects (HTTP 401/403, JSON-RPC `-32600`, or an answer that is not a block number). The failure is a `ProviderConfigurationException` whose message names the chains or `(chain_id, asset)` pairs and the operator action, never the key or the endpoint.
- A probe that meets an outage (`5xx`, `429`, a timeout, a transport error) does not fail startup: the provider starts in the `probe-failed` state with health `DOWN` and the scrubbed error, and the first successful fetch clears it (section 7). An enabled chain without a mapping and without active watched addresses, such as the seeded `local-evm` on a fresh database, is only logged; registering an address there, or enabling one again, answers `404 Unsupported chain`.
- During a sync run, `ProviderConfigurationException` is terminal: the run is marked `FAILED` at once, `failure_attempts` is not spent on retries that cannot succeed, and the checkpoint does not move.
- A configuration gap of one address (its chain without a mapping or, under `configured-block`, without a start block, its asset without an enabled config, a block with more events than a page) fails only that address's runs: provider health stays `UP` with the gap as `lastDataError`, while rejected credentials turn it `DOWN`. The startup preflight still refuses the first three at the next start, so disable such an address or fix its configuration before restarting.
- `sync_runs.last_error`, log lines, health details, and exception messages are scrubbed of the API key; a transport failure is named by its kind, such as `Alchemy transport failure for network eth-sepolia: timeout (SocketTimeoutException).`, without the request URL and without its cause, and the WARN line carries the cause chain with every URL cut out.

Operational signal:

- Startup log `alchemy_preflight_succeeded` with the probed and the unavailable networks, or the startup failure with the scrubbed message; `alchemy_preflight_probe_unavailable` per unavailable network and `alchemy_preflight_unmapped_chains_skipped` for unmapped chains without active addresses.
- Log `alchemy_rpc_failed` and `alchemy_provider_page_fetch_failed` with the scrubbed error.
- Health component `alchemyChainProvider` with `provider`, `authMode`, `networks`, `state`, and the scrubbed `error`. A configuration gap of one address shows only as `lastDataError`, until the next successful fetch of any address, and for good as the run's `last_error`.

## 18. Future Failure Modes

Deferred areas:

- Provider rate limiting shared across instances; the Alchemy token bucket is local to each process.
- Broker-specific delivery errors.
- CDC connector lag.
- Balance projection rebuild failure.
- Multi-tenant authorization failures.
- Distributed scheduler split-brain.

These areas require additional design before implementation.
