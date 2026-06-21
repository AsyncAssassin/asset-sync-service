# Failure Modes Specification

Status: Draft  
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

- The API, fake provider, or future external delivery sends the same observed event more than once.

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

## 7. Provider Timeout

Scenario:

- The fake provider times out or is unavailable during a sync.

Expected behavior:

- Provider call is outside a database transaction.
- The current `sync_run` is marked `FAILED` in a short transaction.
- API returns `503 Service Unavailable`.
- Events already committed before the timeout remain valid.
- No long-lived database locks are held while waiting for provider response.

Operational signal:

- Store concise failure detail in `sync_runs.last_error`.
- Log `syncRunId`, target type, target id, and provider operation.

## 8. Publisher Failure And Retry

Scenario:

- The outbox publisher adapter fails to publish an event.

Expected behavior:

- The outbox event remains durable in PostgreSQL.
- `attempts` is incremented.
- `last_error` is updated with a concise failure summary.
- `status` is set to `FAILED`.
- `next_attempt_at` is moved forward using backoff.
- A later poller run retries due `FAILED` rows.

Operational signal:

- Emit outbox failure metrics when metrics are implemented.
- Keep error messages bounded to avoid unbounded row growth.

## 9. Process Crash Around Outbox Publish

Scenario:

- The process crashes after publishing to the local adapter but before marking the outbox row `PUBLISHED`.

Expected behavior:

- The row remains `NEW` or `FAILED`.
- After restart, the poller may publish it again.
- This is acceptable because outbox delivery is at-least-once.
- Downstream consumers must deduplicate by outbox `id` or `idempotency_key`.

Scenario:

- The process crashes before publishing.

Expected behavior:

- The row remains due and is retried after restart.

## 10. Concurrent Sync For Same Address

Scenario:

- Two clients start sync for the same watched address at the same time.

Expected behavior:

- Duplicate provider work is possible in the MVP.
- Each sync has its own `sync_runs` row.
- Observed event ingestion remains safe through natural unique keys and row locks.
- Duplicate events return `NoChange`.
- Outbox idempotency keys prevent duplicate lifecycle events.

Operational signal:

- Log each sync run independently.
- Track duplicate processing metrics when implemented.

Future extension:

- Add PostgreSQL advisory locks per address or account to reduce duplicate provider work.
- This is not part of the MVP.

## 11. Future Failure Modes

Deferred areas:

- Real provider rate limiting and partial block-range failures.
- Broker-specific delivery errors.
- CDC connector lag.
- Balance projection rebuild failure.
- Multi-tenant authorization failures.
- Distributed scheduler split-brain.

These areas require additional design before implementation.
