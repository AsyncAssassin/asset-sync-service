# Database Specification

Status: Implemented behavior of the current MVP  
Scope: MVP PostgreSQL schema and persistence behavior  
Source of truth: `docs/architecture.md`

## 1. Database Principles

PostgreSQL is the source of truth for accounts, watched addresses, observed transactions, sync runs, and outbox events. Liquibase owns schema evolution. jOOQ is the persistence API used by repositories and application services.

MVP principles:

- Prefer explicit constraints and indexes over application-only validation.
- Use text columns with `CHECK` constraints for MVP enums.
- Use UUID primary keys for API-facing resources.
- Use natural unique keys where idempotency depends on database enforcement.
- Use `READ COMMITTED` with row-level locks and unique constraints for critical races.
- Do not hold database transactions open while calling the chain provider.
- Use the transactional outbox table for durable integration events.

## 2. Liquibase Changelog Structure

Expected changelog layout:

```text
src/main/resources/db/changelog
  db.changelog-master.yaml
  changes
    001-create-accounts-and-chain-configs.yaml
    002-create-watched-addresses.yaml
    003-create-observed-transactions.yaml
    004-create-outbox-and-sync-runs.yaml
    005-seed-local-chain-configs.yaml
    006-add-input-length-constraints.yaml
    007-add-outbox-dead-status.yaml
    008-add-outbox-partial-indexes.yaml
    009-normalize-local-evm-identities.yaml
    010-create-users-and-authorities.yaml
    011-validate-length-constraints.yaml
    012-add-observed-transaction-block-height-check.yaml
    013-async-sync-runs.yaml
    014-provider-pagination-cursors.yaml
    015-add-asset-configs.yaml
    016-add-observed-transaction-source.yaml
```

Changelog rules:

- `db.changelog-master.yaml` includes files in deterministic order.
- Each changeset has a stable author and id.
- Migrations are forward-only during MVP development.
- Seed data is limited to local chain configuration required for fake-provider tests and local runs.
- No PostgreSQL enum types in the MVP; use text plus `CHECK` constraints to keep status evolution simple.
- Data-normalization changesets must fail fast when existing rows would collide after normalization. Operators must manually clean up or backfill those rows before rerunning the migration; changesets must not silently merge or delete business rows.
- Changeset `006` adds input-length constraints as `NOT VALID`, so new writes are protected immediately while pre-existing oversized rows are not scanned during that upgrade step. Changeset `011` validates those constraints; operators with legacy oversized rows must clean them before applying `011`.
- Changeset `009` normalizes local EVM watched addresses and observed transactions. Historical `PUBLISHED` outbox rows are kept as audit history, but pending `NEW` or `FAILED` local-evm outbox rows must already have canonical payload casing and the natural-key `observed-tx:{chainId}:...:status:{status}:v:{version}` idempotency keys of that release; keys built from the transaction id came later and never exist when `009` runs. If not, the migration halts and operators must drain pending outbox rows or perform an audited manual cleanup before retrying.
- Changeset `010` creates the standard Spring Security JDBC `users` and `authorities` tables.
- Changeset `012` adds `observed_transactions.block_height >= 0` as `NOT VALID`, then validates it immediately. Legacy databases with negative block heights must be cleaned before applying `012`; operators can preflight with:

```sql
SELECT id, chain_id, tx_hash, event_index, block_height
FROM observed_transactions
WHERE block_height < 0;
```

If the query returns rows, perform an audited cleanup or backfill before running the migration.
- Changeset `013` converts `sync_runs` into the durable async sync queue. It keeps legacy `STARTED` for recovery compatibility, adds queue/lease fields, and creates a partial unique index for `QUEUED/RUNNING` target single-flight. Do not run mixed old/new application versions that can both accept sync POSTs. Before enabling the async worker, inspect legacy duplicate `STARTED` targets:

```sql
SELECT target_type, target_id, count(*)
FROM sync_runs
WHERE status = 'STARTED'
GROUP BY target_type, target_id
HAVING count(*) > 1;
```

Drain, fail, or explicitly accept any legacy `STARTED` rows before enabling the worker. The worker processes only `QUEUED/RUNNING`; legacy stale-`STARTED` recovery remains separate.
- Changeset `014` adds per-address `sync_cursors` and separates retry budget from worker claim count. Existing watched addresses receive one cursor row with null provider cursor and `{}` checkpoint. Existing `sync_runs.attempts` remains a total claim diagnostic; retry budget is backfilled into `failure_attempts`.
- Changeset `016` adds the nullable `observed_transactions.source` (the source of the row's last lifecycle change: `rest:<user>` or `provider:<type>`) with a 128-character check added `NOT VALID` and validated at once. Existing rows keep `NULL`, which means the source was not recorded; the column is added without a default, so no row is rewritten.
- Changeset `015` adds the `asset_configs` registry keyed by `(chain_id, asset)`, upserts the `eth-sepolia` (enabled, `required_confirmations=1`) and `eth-mainnet` (disabled, `required_confirmations=12`) chain configs, and seeds `USDC` for `local-evm` (deterministic fake contract `0x000000000000000000000000000000000000f001`, decimals 18), `eth-sepolia` (Circle contract, decimals 6, enabled), and `eth-mainnet` (Circle contract, decimals 6, disabled). All seeds use insert-or-update semantics. Registration validation protects only new rows: before pointing a real provider at an existing database, run the rollout preflight below and seed or disable whatever it returns; it must come back empty. With `asset-sync.provider.type=alchemy` the service runs the same check at startup, together with a mapping check for every enabled chain that has enabled asset configs and active watched addresses, and refuses to start while either returns rows. The seeded `local-evm` chain has no Alchemy network; without active watched addresses it is only logged, so a fresh database boots, and disabling it (`UPDATE chain_configs SET enabled = false WHERE chain_id = 'local-evm'`) keeps addresses from being registered there.

```sql
SELECT
    wa.id,
    wa.account_id,
    wa.chain_id,
    wa.address,
    wa.asset
FROM watched_addresses wa
JOIN chain_configs cc
    ON cc.chain_id = wa.chain_id
LEFT JOIN asset_configs ac
    ON ac.chain_id = wa.chain_id
    AND ac.asset = upper(wa.asset)
    AND ac.enabled = true
WHERE wa.status = 'ACTIVE'
  AND cc.enabled = true
  AND ac.chain_id IS NULL
ORDER BY wa.chain_id, wa.asset, wa.address;
```

## 3. Tables

### `accounts`

Purpose: logical grouping for watched addresses.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | no | Primary key |
| `external_ref` | `text` | yes | Optional caller reference |
| `status` | `text` | no | `ACTIVE` or `DISABLED` |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (id)`
- `unique (external_ref)` where `external_ref is not null`
- `check (status in ('ACTIVE', 'DISABLED'))`

Notes:

- `external_ref` is not mandatory because the service can own account identity internally.
- Disabling an account must not delete historical transaction data.

### `chain_configs`

Purpose: per-chain configuration used by the state machine and API validation.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `chain_id` | `text` | no | Primary key |
| `display_name` | `text` | no | Human-readable name |
| `required_confirmations` | `integer` | no | Confirmation threshold |
| `enabled` | `boolean` | no | Whether the chain can be used |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (chain_id)`
- `check (required_confirmations >= 0)`

Notes:

- Confirmation thresholds are data, not code constants.
- The Alchemy provider reads every block once, so the confirmations of its events never grow after the scan; keep `required_confirmations` of an Alchemy chain at or below the depth of its finality frontier, or its events stay `SEEN` (`docs/alchemy-runbook.md`, section 2).
- The MVP should seed at least one local chain id for fake-provider flows.

### `asset_configs`

Purpose: registry that resolves a chain's public asset code to its token identity and gates watched-address registration.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `chain_id` | `text` | no | References `chain_configs(chain_id)` |
| `asset` | `text` | no | Public asset code used by the API, upper-case, for example `USDC` |
| `token_standard` | `text` | no | `ERC20` for now |
| `contract_address` | `text` | no | Lower-case EVM address, `0x` plus 40 hex characters |
| `decimals` | `integer` | no | Decimal places for raw value conversion, `0..18` |
| `display_name` | `text` | yes | Optional human name |
| `enabled` | `boolean` | no | Registration and sync allowed only when true |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (chain_id, asset)`
- `foreign key (chain_id) references chain_configs(chain_id)`
- `unique (chain_id, contract_address)`
- `check (asset = upper(asset))`, `check (token_standard in ('ERC20'))`
- `check (contract_address = lower(contract_address))`, `check (contract_address ~ '^0x[0-9a-f]{40}$')`
- `check (decimals between 0 and 18)`, length checks on `chain_id`, `asset`, and `display_name`

Notes:

- Every watched-address registration requires an enabled row for the normalized `(chain_id, asset)`; there is no profile or provider bypass, and the seeded `local-evm` `USDC` row keeps local, test, demo, and e2e flows working.
- The fake provider and the demo simulator ignore `contract_address`; real provider adapters resolve the contract and decimals through this table.
- Watched addresses carry no foreign key to this table, so legacy rows are checked by the rollout preflight query in section 2, which the Alchemy provider type also runs at startup.

### `users`

Purpose: Spring Security JDBC user store for non-local/test profiles and demo users.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `username` | `varchar(64)` | no | Primary key |
| `password` | `varchar(200)` | no | Encoded password |
| `enabled` | `boolean` | no | Whether the user can authenticate |

Constraints and indexes:

- `primary key (username)`

### `authorities`

Purpose: Spring Security JDBC authorities for role-based endpoint access.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `username` | `varchar(64)` | no | References `users(username)` |
| `authority` | `varchar(64)` | no | Granted authority, such as `ROLE_READ` or `ROLE_OPERATOR` |

Constraints and indexes:

- `foreign key (username) references users(username)`
- `unique (username, authority)`
- `index (username)`

### `watched_addresses`

Purpose: public addresses and assets registered for observation.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | no | Primary key |
| `account_id` | `uuid` | no | References `accounts(id)` |
| `chain_id` | `text` | no | References `chain_configs(chain_id)` |
| `address` | `text` | no | Public address |
| `asset` | `text` | no | Asset id or symbol |
| `label` | `text` | yes | Optional display label |
| `status` | `text` | no | `ACTIVE` or `DISABLED` |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (id)`
- `foreign key (account_id) references accounts(id)`
- `foreign key (chain_id) references chain_configs(chain_id)`
- `unique (chain_id, address, asset)`
- `index (account_id)`
- `index (chain_id, address)`
- `check (status in ('ACTIVE', 'DISABLED'))`

Natural key:

```text
chain_id + address + asset
```

Notes:

- The natural key prevents duplicate observation for the same public fact.
- Account-level sync depends on the `account_id` index.
- Provider lookup and diagnostics depend on the `chain_id, address` index.

### `observed_transactions`

Purpose: canonical stored representation of observed transaction lifecycle state.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | no | Primary key |
| `chain_id` | `text` | no | Chain id from the event |
| `tx_hash` | `text` | no | Provider transaction hash |
| `event_index` | `integer` | no | Provider-specific event discriminator |
| `watched_address_id` | `uuid` | no | References `watched_addresses(id)` |
| `address` | `text` | no | Observed public address |
| `asset` | `text` | no | Asset id or symbol |
| `direction` | `text` | no | `INBOUND` or `OUTBOUND` |
| `amount` | `numeric(38, 18)` | no | Non-negative amount |
| `block_height` | `bigint` | no | Provider block height |
| `confirmations` | `integer` | no | Stored confirmation count |
| `status` | `text` | no | `SEEN`, `CONFIRMED`, or `REVERTED` |
| `first_seen_at` | `timestamptz` | no | First persisted observation |
| `last_seen_at` | `timestamptz` | no | Last accepted observation |
| `confirmed_at` | `timestamptz` | yes | Set once confirmation is reached |
| `reverted_at` | `timestamptz` | yes | Set once reverted |
| `version` | `bigint` | no | Diagnostic version, default `0` |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |
| `source` | `text` | yes | Source of the last lifecycle change: `rest:<user>` (`rest:anonymous` where nobody authenticates) or `provider:<http\|alchemy\|fake>`; `NULL` for rows written before changeset `016` and for rows the demo seeder inserts directly |

Constraints and indexes:

- `primary key (id)`
- `foreign key (watched_address_id) references watched_addresses(id)`
- `unique (chain_id, tx_hash, event_index, address, asset)`
- `index (watched_address_id, status)`
- `index (chain_id, tx_hash)`
- `index (chain_id, block_height)`
- `check (event_index >= 0)`
- `check (amount >= 0)`
- `check (block_height >= 0)`
- `check (confirmations >= 0)`
- `check (direction in ('INBOUND', 'OUTBOUND'))`
- `check (status in ('SEEN', 'CONFIRMED', 'REVERTED'))`

Natural idempotency key:

```text
chain_id + tx_hash + event_index + address + asset
```

Immutable fields after insert:

- `chain_id`
- `tx_hash`
- `event_index`
- `address`
- `asset`
- `direction`
- `amount`

Mutable fields:

- `block_height`
- `confirmations`
- `status`
- `last_seen_at`
- `confirmed_at`
- `reverted_at`
- `updated_at`
- `version`

Notes:

- The natural unique key is the primary database guard for duplicate observed events.
- Existing rows must be loaded `FOR UPDATE` before transition evaluation.
- `block_height` remains mutable to tolerate provider corrections before final confirmation.
- Lower confirmation counts are stale and must not reduce the stored value.

### `outbox_events`

Purpose: durable integration events created in the same transaction as observed transaction lifecycle changes.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | no | Primary key and event id |
| `aggregate_type` | `text` | no | MVP value: `OBSERVED_TRANSACTION` |
| `aggregate_id` | `uuid` | no | Observed transaction id |
| `event_type` | `text` | no | Transaction lifecycle event type |
| `idempotency_key` | `text` | no | Unique lifecycle event key, `observed-tx:{transactionId}:status:{status}:v:{version}` |
| `payload` | `jsonb` | no | Event payload |
| `status` | `text` | no | `NEW`, `PUBLISHED`, `FAILED`, or `DEAD` |
| `attempts` | `integer` | no | Publish attempts, default `0` |
| `next_attempt_at` | `timestamptz` | no | Earliest retry time, and the processing lease token while a row is claimed |
| `published_at` | `timestamptz` | yes | Publish success timestamp |
| `last_error` | `text` | yes | Last publish failure |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (id)`
- `unique (idempotency_key)`
- `index (status, next_attempt_at)`
- partial due index on `(next_attempt_at, created_at, id) where status in ('NEW', 'FAILED')`
- partial retention index on `(published_at, id) where status = 'PUBLISHED'`
- `index (aggregate_type, aggregate_id)`
- `check (status in ('NEW', 'PUBLISHED', 'FAILED', 'DEAD'))`
- `check (attempts >= 0)`
- `check (event_type in ('TRANSACTION_SEEN', 'TRANSACTION_CONFIRMED', 'TRANSACTION_REVERTED'))`

Idempotency key format:

```text
observed-tx:{transactionId}:status:{newStatus}:v:{version}
```

Rows written by earlier versions keep their natural-key `observed-tx:{chainId}:{txHash}:{eventIndex}:{address}:{asset}:status:{newStatus}:v:{version}` keys. A key of the current format holds a UUID where those hold five natural-key parts, so the two formats never collide.

Poller query requirement:

```sql
SELECT *
FROM outbox_events
WHERE status IN ('NEW', 'FAILED')
  AND next_attempt_at <= now()
ORDER BY created_at
LIMIT ?
FOR UPDATE SKIP LOCKED
```

Notes:

- The MVP publisher writes to a local adapter such as structured logs.
- Claiming due rows happens in a short transaction that moves `next_attempt_at` to `now + processing_lease`; this releases row locks while preventing another poller from immediately reclaiming the same row.
- Publish completion runs per event in its own short transaction and is fenced with `WHERE id = ? AND status IN ('NEW', 'FAILED') AND next_attempt_at = :claimedLeaseUntil`.
- A completion update that affects zero rows is stale and must not overwrite the newer owner state.
- `FAILED` rows retry with bounded backoff until max attempts; after that they become terminal `DEAD` rows and are excluded from the due/backlog set.
- Published-row retention deletes old `PUBLISHED` rows in batches when enabled.
- Delivery is at-least-once. Consumers must deduplicate by `id` or `idempotency_key`.
- A process crash after publish but before marking `PUBLISHED` may produce duplicate delivery.

### `sync_runs`

Purpose: durable queue and diagnostic record for manual or scheduled sync execution.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | no | Primary key |
| `target_type` | `text` | no | `ACCOUNT` or `ADDRESS` |
| `target_id` | `uuid` | no | Account id or watched address id |
| `status` | `text` | no | `STARTED`, `QUEUED`, `RUNNING`, `SUCCEEDED`, or `FAILED` |
| `queued_at` | `timestamptz` | no | Queue timestamp |
| `started_at` | `timestamptz` | yes | First provider start timestamp; null while fresh `QUEUED` |
| `finished_at` | `timestamptz` | yes | End timestamp |
| `events_seen` | `integer` | no | Provider events observed |
| `events_changed` | `integer` | no | Events that changed stored state |
| `last_error` | `text` | yes | Failure detail for diagnostics |
| `attempts` | `integer` | no | Worker claim attempts |
| `failure_attempts` | `integer` | no | Retryable failure budget counter |
| `continuation_count` | `integer` | no | Healthy continuation requeue counter |
| `run_checkpoint` | `jsonb` | no | Run-local metadata: the account-sync pass (`accountPass`: scan keyset, scan completion, visited count, deferred busy addresses, failed addresses) |
| `last_requeue_reason` | `text` | yes | `FAILURE`, `CONTINUATION`, or `LEASE_BUSY` |
| `next_attempt_at` | `timestamptz` | no | Earliest claim/retry time |
| `locked_by` | `varchar(200)` | yes | Current worker owner for `RUNNING` |
| `lock_token` | `uuid` | yes | Current claim token for fenced updates |
| `locked_until` | `timestamptz` | yes | Lease expiry for `RUNNING` |
| `heartbeat_at` | `timestamptz` | yes | Last heartbeat timestamp |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (id)`
- `index (target_type, target_id, started_at desc)`
- `index (status, started_at desc)`
- partial due index on `(next_attempt_at, queued_at, id) where status = 'QUEUED'`
- partial expired-running index on `(locked_until, started_at, id) where status = 'RUNNING'`
- partial unique in-flight target index on `(target_type, target_id) where status in ('QUEUED','RUNNING')`
- `index (target_type, target_id, queued_at desc)`
- `check (target_type in ('ACCOUNT', 'ADDRESS'))`
- `check (status in ('STARTED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))`
- `check (events_seen >= 0)`
- `check (events_changed >= 0)`
- `check (attempts >= 0)`
- `check (failure_attempts >= 0)`
- `check (continuation_count >= 0)`
- `check (jsonb_typeof(run_checkpoint) = 'object')`
- `check (last_requeue_reason is null or last_requeue_reason in ('FAILURE','CONTINUATION','LEASE_BUSY'))`
- lock fields are required for `RUNNING` and null for non-`RUNNING`
- terminal rows require `finished_at`; queued/running/started rows require `finished_at is null`

Notes:

- `sync_runs` are operational records and the durable queue for async sync.
- They do not participate in observed transaction idempotency.
- Healthy provider pagination continuations increment `continuation_count`, not `failure_attempts`.
- Requeues caused by a rejected executor submission or by a worker shutdown also carry `last_requeue_reason = FAILURE` but leave `failure_attempts` unchanged; `last_error` names the cause.
- Retryable provider failures, 429 throttling, capacity failures, and expired `RUNNING` recovery increment `failure_attempts`.
- The partial unique in-flight index intentionally excludes legacy `STARTED`.
- No retention job removes finished runs, so every sync request adds a row for good. No other table references `sync_runs`, and a finished run is only read back by `GET /api/v1/sync-runs/{id}`, so old terminal runs can be deleted by hand:

```sql
DELETE FROM sync_runs
WHERE status IN ('SUCCEEDED', 'FAILED')
  AND finished_at < now() - interval '30 days';
```

### `sync_cursors`

Purpose: per-watched-address provider checkpoint and lease. This table prevents direct address sync and account sync from advancing the same address checkpoint concurrently.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `watched_address_id` | `uuid` | no | Primary key and FK to `watched_addresses(id)` |
| `provider_cursor` | `text` | yes | Opaque provider resume token; kept after an empty final page without a cursor, cleared after a final page with events and without one |
| `checkpoint` | `jsonb` | no | Provider metadata object |
| `last_processed_block_height` | `bigint` | yes | Durable high-water block |
| `last_processed_event_index` | `integer` | yes | Durable high-water event index |
| `last_finalized_block_height` | `bigint` | yes | Provider safe/finalized height |
| `version` | `bigint` | no | Checkpoint CAS version |
| `locked_by` | `varchar(200)` | yes | Current cursor lease owner |
| `lock_token` | `uuid` | yes | Cursor lease token |
| `locked_until` | `timestamptz` | yes | Cursor lease expiry |
| `cursor_updated_at` | `timestamptz` | yes | Last checkpoint advancement |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (watched_address_id)`
- `foreign key (watched_address_id) references watched_addresses(id) on delete cascade`
- cursor length, checkpoint object/length, non-negative high-water fields, non-negative version
- lease fields are all null or all populated
- partial indexes for expired leases and locked owners

Notes:

- Provider fetches never run inside the cursor lease transaction. The worker acquires the lease, fetches one page outside a DB transaction, ingests the full page, then advances the checkpoint with `locked_by + lock_token + version + locked_until >= now` fencing.
- A cursor heartbeat extends `locked_until` while a page fetch or ingest is in progress. If the heartbeat or the pre-checkpoint lease extension fails, the worker treats the checkpoint owner as stale and does not advance.
- `advanceCheckpointFenced` preserves high-water columns when a final empty page or cursor-only page supplies null block fields. SQL also uses `COALESCE` as defense in depth. Final empty pages without `nextCursor` are valid only when the provider supplies durable block high-water such as `safeBlockHeight` or `latestBlockHeight`.
- A failed checkpoint advance after committed events is safe: retry starts from the old cursor and replays the page idempotently.
- Under the Alchemy provider `provider_cursor` is `{"v":1,"p":"alchemy","nextBlock":N}` (always a block boundary, never a `pageKey` or log index) and `checkpoint` holds the scan diagnostics: provider, chain, network, asset, contract, `scan` mode and counters, `nextBlock`, latest and safe heights, finality mode and fallback flag, `initialStartBlock`, `highWaterAdjusted`, and the skip counters.

## 4. Transaction Boundaries

Use short Spring-managed database transactions for:

- Account creation.
- Watched address registration.
- Single observed event ingestion.
- Sync run queue insertion, claim, heartbeat, requeue, and final status update.
- Outbox batch claiming.
- Per-event outbox completion updates.

Provider calls must run outside database transactions. `asset-sync.sync.provider-timeout` is the deadline for one provider page fetch. A provider timeout must not hold row locks or an open connection.

Outbox publishing uses `next_attempt_at` as a lease token rather than a separate `PROCESSING` status. The poller claims a small due batch with `FOR UPDATE SKIP LOCKED`, immediately updates each claimed row's `next_attempt_at` to the lease deadline, and commits. It then publishes outside the claim transaction. Each completion update records `PUBLISHED`, `FAILED`, or `DEAD` in a separate short transaction fenced by the exact claimed lease value.
If `publish()` succeeds but `markPublished()` fails, the row remains leased in its previous `NEW` or `FAILED` status until the lease expires. That completion failure is logged and metered separately; it does not consume a publish attempt and cannot move the row to `DEAD`.

Recommended sync sequence:

```text
1. Insert sync_run with status QUEUED in a short transaction, or return the existing QUEUED/RUNNING run for the same target.
2. Worker claims due QUEUED rows with FOR UPDATE SKIP LOCKED and writes RUNNING lease fields.
3. Worker acquires a `sync_cursors` lease for each watched address it processes.
4. Worker calls the active provider for one bounded page outside a database transaction.
5. Ingest each observed event in the page in its own transaction.
6. Advance the cursor checkpoint only after the whole page was ingested.
7. Fenced update marks sync_run SUCCEEDED, FAILED, or QUEUED for retry/continuation.
```

Observed event ingestion transaction:

```text
1. Resolve active watched address by chain_id + address + asset.
2. Load existing observed transaction by natural key FOR UPDATE.
3. Evaluate transition using Spring-independent domain logic.
4. Insert or update observed_transactions if needed.
5. Insert outbox_events row for meaningful lifecycle change.
6. Commit both transaction state and outbox event atomically.
```

## 5. Locking And Concurrency

Observed transaction ingestion:

- Existing rows are locked with `FOR UPDATE`.
- Concurrent first inserts are guarded by the natural unique constraint.
- On unique-constraint race, the loser should reload the row `FOR UPDATE` and evaluate as a duplicate or update.

Outbox polling:

- Poller claims due `NEW` or `FAILED` events with `FOR UPDATE SKIP LOCKED` and writes a lease deadline to `next_attempt_at`.
- Multiple poller instances may run safely without claiming the same row while the lease is active.
- Publish status updates are compare-and-set updates fenced by the exact claimed lease value.
- `DEAD` and `PUBLISHED` rows are terminal for claiming.

Concurrent sync:

- Concurrent sync for the same address/account returns one in-flight `QUEUED/RUNNING` run.
- Duplicate provider work can still happen after crash/recovery because execution is at-least-once.
- Event ingestion remains safe through row locks and unique constraints.
- Advisory locks or a counter table are future options for stricter global queue caps.

Isolation:

- `READ COMMITTED` is sufficient for MVP critical paths because uniqueness and row locks protect state transitions and outbox creation.

## 6. jOOQ Generation Expectations

Expected build behavior in later phases:

- Liquibase migrations define the schema used by local PostgreSQL and Testcontainers PostgreSQL.
- jOOQ code generation reads the PostgreSQL schema and generates table, record, and enum-like accessors.
- Generated jOOQ code is used only from infrastructure persistence components.
- API controllers and domain state machine code must not depend on generated jOOQ types.
- Repository methods return application/domain snapshots, not database records.

Generation configuration should align Kotlin nullability with database nullability where supported.

## 7. Future Extensions

Deferred database capabilities:

- Balance projection tables derived from observed transactions.
- Advisory locks or scheduler locks for multi-instance sync coordination.
- Audit/event-history tables.
- Partitioning or retention policies for high-volume transaction history.
- Broker-specific publisher metadata.

These extensions must preserve observed transactions and PostgreSQL constraints as the source of truth for lifecycle state.
