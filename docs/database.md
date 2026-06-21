# Database Specification

Status: Draft  
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
```

Changelog rules:

- `db.changelog-master.yaml` includes files in deterministic order.
- Each changeset has a stable author and id.
- Migrations are forward-only during MVP development.
- Seed data is limited to local chain configuration required for fake-provider tests and local runs.
- No PostgreSQL enum types in the MVP; use text plus `CHECK` constraints to keep status evolution simple.

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
- The MVP should seed at least one local chain id for fake-provider flows.

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

Constraints and indexes:

- `primary key (id)`
- `foreign key (watched_address_id) references watched_addresses(id)`
- `unique (chain_id, tx_hash, event_index, address, asset)`
- `index (watched_address_id, status)`
- `index (chain_id, tx_hash)`
- `index (chain_id, block_height)`
- `check (event_index >= 0)`
- `check (amount >= 0)`
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
| `idempotency_key` | `text` | no | Unique lifecycle event key |
| `payload` | `jsonb` | no | Event payload |
| `status` | `text` | no | `NEW`, `PUBLISHED`, or `FAILED` |
| `attempts` | `integer` | no | Publish attempts, default `0` |
| `next_attempt_at` | `timestamptz` | no | Earliest retry time |
| `published_at` | `timestamptz` | yes | Publish success timestamp |
| `last_error` | `text` | yes | Last publish failure |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (id)`
- `unique (idempotency_key)`
- `index (status, next_attempt_at)`
- `index (aggregate_type, aggregate_id)`
- `check (status in ('NEW', 'PUBLISHED', 'FAILED'))`
- `check (attempts >= 0)`
- `check (event_type in ('TRANSACTION_SEEN', 'TRANSACTION_CONFIRMED', 'TRANSACTION_REVERTED'))`

Idempotency key format:

```text
observed-tx:{chainId}:{txHash}:{eventIndex}:{address}:{asset}:status:{newStatus}
```

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
- Delivery is at-least-once. Consumers must deduplicate by `id` or `idempotency_key`.
- A process crash after publish but before marking `PUBLISHED` may produce duplicate delivery.

### `sync_runs`

Purpose: diagnostic record for manual or scheduled sync execution.

Key columns:

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | no | Primary key |
| `target_type` | `text` | no | `ACCOUNT` or `ADDRESS` |
| `target_id` | `uuid` | no | Account id or watched address id |
| `status` | `text` | no | `STARTED`, `SUCCEEDED`, or `FAILED` |
| `started_at` | `timestamptz` | no | Start timestamp |
| `finished_at` | `timestamptz` | yes | End timestamp |
| `events_seen` | `integer` | no | Provider events observed |
| `events_changed` | `integer` | no | Events that changed stored state |
| `last_error` | `text` | yes | Failure detail for diagnostics |
| `created_at` | `timestamptz` | no | Creation timestamp |
| `updated_at` | `timestamptz` | no | Last update timestamp |

Constraints and indexes:

- `primary key (id)`
- `index (target_type, target_id, started_at desc)`
- `index (status, started_at desc)`
- `check (target_type in ('ACCOUNT', 'ADDRESS'))`
- `check (status in ('STARTED', 'SUCCEEDED', 'FAILED'))`
- `check (events_seen >= 0)`
- `check (events_changed >= 0)`

Notes:

- `sync_runs` are operational records.
- They do not participate in observed transaction idempotency.

## 4. Transaction Boundaries

Use short Spring-managed database transactions for:

- Account creation.
- Watched address registration.
- Single observed event ingestion.
- Sync run creation.
- Sync run final status update.
- Outbox batch claiming and status updates.

Provider calls must run outside database transactions. A provider timeout must not hold row locks or an open connection.

Outbox publishing in the MVP uses a local publisher adapter and the schema has no separate `PROCESSING` or lease status. The poller therefore claims a small due batch with `FOR UPDATE SKIP LOCKED`, invokes the local publisher for each claimed row, records `PUBLISHED` or `FAILED`, and commits the batch. Keep the batch size and publisher timeout bounded so row locks are short-lived. A future real broker adapter can introduce a claim/lease status before moving network publishing outside the claim transaction.

Recommended sync sequence:

```text
1. Insert sync_run with status STARTED in a short transaction.
2. Call the fake provider outside a database transaction.
3. Ingest each observed event in its own transaction.
4. Update sync_run to SUCCEEDED or FAILED in a short transaction.
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

- Poller claims due `NEW` or `FAILED` events with `FOR UPDATE SKIP LOCKED`.
- Multiple poller instances may run safely without claiming the same row in the same batch.
- Publish status updates must be idempotent.
- MVP publishing is local and bounded while claimed rows are locked.

Concurrent sync:

- Concurrent sync for the same address can duplicate provider work in the MVP.
- Event ingestion remains safe through row locks and unique constraints.
- Advisory locks per address or account are a future extension, not part of MVP.

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
- Provider cursor tables for real chain scans.
- Advisory locks or scheduler locks for multi-instance sync coordination.
- Audit/event-history tables.
- Partitioning or retention policies for high-volume transaction history.
- Broker-specific publisher metadata.

These extensions must preserve observed transactions and PostgreSQL constraints as the source of truth for lifecycle state.
