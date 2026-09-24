# asset-sync-service Architecture

Status: MVP implemented through async sync and provider pagination
Scope: MVP backend service  
Stack: Kotlin, Spring Boot 3.x, Spring MVC, jOOQ, PostgreSQL, Liquibase, Testcontainers, Docker Compose, OpenAPI, transactional outbox  

## 1. Assumptions

- `eventIndex` is required and represents the provider-specific event discriminator inside a transaction. It covers log indexes, output indexes, or similar chain-specific positions.
- `asset` is represented as a string asset id or symbol in the MVP.
- A watched address is unique by `chainId + address + asset`.
- The chain provider is selected by profile and configuration: fake/in-memory in `local` and `test`; elsewhere `asset-sync.provider.type` picks the HTTP bridge adapter (`http`, the default) or the Alchemy adapter (`alchemy`), and exactly one `ChainProviderPort` bean exists.
- PostgreSQL is the source of truth for accounts, watched addresses, observed transactions, sync runs, and outbox events.
- The outbox publisher writes to a local publishing adapter or structured logs in the MVP.
- Balance projection is not part of the first MVP.
- External event delivery is at-least-once. Downstream consumers must be idempotent.

## 2. Non-Goals

- No bundled real blockchain node or indexer backend in the MVP.
- No private key, seed phrase, or signing material handling.
- No transaction signing.
- No custody functionality.
- No real funds movement.
- No balance projection in the MVP.
- No Kafka or SQS publisher in the MVP.
- No Redis dependency in the MVP.
- No WebFlux or coroutines in the MVP unless a later requirement justifies them.

## 3. Project Description

`asset-sync-service` is a backend service that synchronizes public account, address, and asset state from observable transaction events. It tracks public facts such as `chainId`, `address`, `asset`, `txHash`, `eventIndex`, `amount`, `blockHeight`, `confirmations`, `direction`, and `status`. The service does not store private keys, sign transactions, provide wallet functionality, or move funds. It accepts events directly through an API or obtains them from the active chain provider during sync (`FakeChainProvider` in `local`/`test`; elsewhere `HttpChainProvider` or `AlchemyChainProvider`, as `asset-sync.provider.type` selects). Events are processed through an idempotent state machine and persisted in PostgreSQL. Meaningful transaction state changes create transactional outbox events in the same database transaction.

## 4. Main Use Cases

- Create an account.
- Register a watched address for an account.
- Start sync by watched address.
- Start sync by account.
- Ingest an observed transaction event.
- Process duplicate events idempotently.
- Update confirmations and transition transactions to `CONFIRMED`.
- Simulate a reorg and transition transactions to `REVERTED`.
- Create outbox events for meaningful transaction state changes.
- Publish outbox events through a scheduled poller.
- Inspect sync runs. Transaction listing/read APIs are deferred beyond the current MVP implementation.

## 5. High-Level Architecture

The service is a blocking Spring MVC application. The main database is PostgreSQL. Schema evolution is managed by Liquibase. Persistence access uses jOOQ.

```text
REST API / Sync Worker / Sync Recovery
        |
        v
Application Services
        |
        +--> ChainProviderPort -> Fake Chain Provider (local/test)
        |                  \-> HTTP Bridge Provider (asset-sync.provider.type=http)
        |                  \-> Alchemy JSON-RPC Provider (asset-sync.provider.type=alchemy)
        |
        +--> Domain State Machine
        |
        v
jOOQ Repositories
        |
        v
PostgreSQL
        |
        v
Outbox Publisher Poller
```

### Layers

API layer:
- Exposes versioned REST endpoints.
- Owns request/response DTOs.
- Performs request validation.
- Maps domain/application errors to `ProblemDetail` responses.
- Does not use jOOQ directly.

Application layer:
- Owns use-case orchestration.
- Defines transaction boundaries.
- Calls provider ports, domain policies, and repositories.
- Coordinates observed event ingestion, sync lifecycle, and outbox creation.

Domain layer:
- Contains Spring-independent state transition logic.
- Defines domain enums, value objects, transition results, and policies.
- Does not depend on persistence, HTTP, or scheduling concerns.

Infrastructure layer:
- Implements repositories with jOOQ.
- Implements the chain providers: fake in `local`/`test`, HTTP bridge or Alchemy elsewhere, selected by `asset-sync.provider.type`; every HTTP-only bean shares one composed condition and every Alchemy-only bean another, so the two never mix.
- Runs the Alchemy startup preflight when `type=alchemy`: static validation of `asset-sync.provider.alchemy.*`, registry rules (a network mapping for every enabled chain with enabled asset configs and active watched addresses, `ERC20` only, no active watched address without an enabled asset config), and one `eth_blockNumber` auth probe per required network, all during bean creation and therefore before the sync worker starts. A rule violation or a rejected probe fails the context; a probe that meets an outage starts the provider in the `probe-failed` state instead.
- Implements the outbox publisher adapter.
- Runs the scheduled jobs: `SyncRunWorkerJob` claims queued sync runs and executes them; `SyncRunRecoveryJob` fails stale legacy runs, requeues `RUNNING` runs whose lease expired, and clears expired cursor leases; `OutboxPublisherJob` publishes due outbox rows; `OutboxRetentionJob` deletes old published rows.
- Configures Liquibase, OpenAPI, metrics, logging, and health checks.

### Why jOOQ Instead of Spring Data JPA

jOOQ is the primary persistence choice for the MVP because the core behavior depends on database-native reliability primitives:

- `INSERT ... ON CONFLICT` for idempotent upserts.
- Row-level locking for concurrent event processing.
- `FOR UPDATE SKIP LOCKED` for outbox polling.
- JSONB outbox payloads.
- Explicit constraints and indexes.
- Clear SQL for state transitions and conflict handling.

Spring Data JPA is viable for simpler CRUD-heavy services, but this service is centered on database constraints, idempotency, and transactional edge cases. With JPA, the critical parts would likely become native SQL anyway. jOOQ keeps the database behavior explicit while still integrating cleanly with Spring transactions.

## 6. Sequence Flows

### Account And Watched Address Registration

```mermaid
sequenceDiagram
    participant Client
    participant API as AccountController
    participant App as AccountApplicationService
    participant Repo as jOOQ Repositories
    participant DB as PostgreSQL

    Client->>API: POST /api/v1/accounts
    API->>App: createAccount(command)
    App->>Repo: insert account
    Repo->>DB: INSERT accounts
    DB-->>Repo: account row
    Repo-->>App: Account
    App-->>API: AccountResponse
    API-->>Client: 201 Created

    Client->>API: POST /api/v1/accounts/{id}/addresses
    API->>App: registerWatchedAddress(command)
    App->>Repo: insert watched address
    Repo->>DB: INSERT watched_addresses
    DB-->>Repo: watched address row
    Repo-->>App: WatchedAddress
    App-->>API: WatchedAddressResponse
    API-->>Client: 201 Created
```

### Manual Address Sync

```mermaid
sequenceDiagram
    participant Client
    participant API as SyncController
    participant Sync as SyncApplicationService
    participant Worker as SyncRunWorkerJob
    participant Cursor as SyncCursorRepository
    participant Provider as ActiveChainProvider
    participant Ingest as ObservedTransactionIngestionService
    participant DB as PostgreSQL

    Client->>API: POST /api/v1/addresses/{addressId}/sync
    API->>Sync: enqueue address sync
    Sync->>DB: insert or reuse sync_run QUEUED
    API-->>Client: 202 Accepted + Location
    Worker->>DB: claim due QUEUED run FOR UPDATE SKIP LOCKED
    Worker->>DB: mark RUNNING with lease + lock_token
    Worker->>Cursor: acquire per-address cursor lease
    loop provider pages
        Worker->>Provider: fetchEventsPage(address, cursor, limit)
        Provider-->>Worker: events + nextCursor/high-water + hasMore
        Worker->>Worker: validate page contract
        loop each page event
            Worker->>Ingest: ingest(event)
            Ingest->>DB: transactional upsert + outbox
        end
        Worker->>Cursor: advance checkpoint fenced by lease token + version + unexpired lease
    end
    Worker->>Cursor: release cursor lease
    Worker->>DB: fenced mark SUCCEEDED, FAILED, or QUEUED continuation/retry
    Client->>API: GET /api/v1/sync-runs/{id}
    API-->>Client: current run state
```

The provider call is outside the observed event ingestion transaction and outside the POST request. `asset-sync.sync.provider-timeout` is the deadline for one provider page fetch, and `asset-sync.sync.pagination.max-provider-page-bytes` bounds HTTP provider response bodies before JSON parsing. When the deadline cancels a fetch, the provider thread stops reading at its next read, within one `asset-sync.provider.read-timeout`, and a body that runs over the limit, is still arriving at the cancel, or belongs to an error status is closed unread instead of drained, so a slow or endless response cannot hold the provider pool. Response headers that trickle in are bounded only by the read timeout of each read. Checkpoints advance only after all events in a provider page have been ingested. Completion and requeue are fenced by `locked_by`, `lock_token`, and `attempts`; cursor checkpoint advancement is separately fenced by `locked_by`, `lock_token`, cursor `version`, and an unexpired `locked_until`. A cursor heartbeat extends the per-address lease during long page fetch or ingest work, and a worker that cannot extend the lease stops before advancing the checkpoint.

The page contract is append-only. Events inside a page must arrive in non-decreasing `(blockHeight, eventIndex, txHash)` order, and the first event of a page must not sit behind the stored `last_processed_block_height` / `last_processed_event_index` checkpoint; a page that violates either rule is rejected as terminal provider-data-invalid and the checkpoint does not move. Lifecycle updates for events that are already behind the checkpoint therefore never travel through the sync path: they arrive through `POST /api/v1/observed-events`, and provider adapters are expected to emit only finalized events.

On shutdown the worker acts as a Spring `SmartLifecycle` in the web server's graceful-shutdown phase: it stops claiming, waits up to `asset-sync.sync.worker.shutdown-timeout` for in-flight runs, then interrupts the rest. Interrupted runs return to `QUEUED` without consuming their retry budget, and their cursor leases are released first.

### HTTP Bridge Page Contract

`HttpChainProvider` speaks to a normalized HTTP bridge: an indexer in `prod`, the bundled simulator in `demo`.

Request: `GET {base-url}/v1/chains/{chainId}/addresses/{address}/events` with the query parameters `asset`, `limit` (the page size), `cursor` (the stored resume token, absent before the first page), and, once the address has a checkpoint, `fromBlockHeight` and `fromEventIndex` of its last processed event. When `asset-sync.provider.auth-header-value` is set, every request carries it as the bridge credential in the header named by `asset-sync.provider.auth-header-name` (`Authorization` by default); a user name or password in `base-url` stops startup instead, because the HTTP client never sends them. Every path and query value is percent-encoded as a whole, so an opaque cursor reaches the bridge exactly as the bridge sent it, `+`, `/`, `=`, `&`, and braces included. A bridge resumes from `cursor` when one is sent, otherwise from the first event at or after `(fromBlockHeight, fromEventIndex)`, and from the start of its history when neither is sent. Serving the checkpoint event itself again is harmless; serving anything before it fails the page.

Response body, at most `max-provider-page-bytes`, with no string longer than 100 000 characters in the fields below; other fields are ignored:

- `events` (required): objects with `txHash`, `eventIndex`, `address`, `asset`, `amount` (a JSON number or decimal string), `blockHeight`, `confirmations`, `direction` (`INBOUND` or `OUTBOUND`), and `status` (`SEEN`, `CONFIRMED`, or `REVERTED`), in non-decreasing `(blockHeight, eventIndex, txHash)` order and at most `limit` of them. A page carries one row per `(txHash, eventIndex)`: an exact repeat is harmless, while a repeat with another direction or amount fails the page before anything is written, so a transfer of the address to itself is left out, as the Alchemy adapter does.
- `hasMore` (required): whether another page follows now; `true` requires a `nextCursor` that differs from the request cursor.
- `nextCursor` or `resumeCursor`: the opaque token for the next request; when both are sent they must be equal. A final page may omit it, with or without new events or heights. After such a page the stored cursor is kept when the page had no events and cleared when it had some, because replaying from the old cursor would return those events behind the checkpoint; the next request carries the stored cursor or the checkpoint either way.
- `latestBlockHeight`, `safeBlockHeight`: block high-water, optional; `safeBlockHeight` must not exceed `latestBlockHeight`.
- `metadata`: an optional JSON object stored as the address checkpoint, at most `max-checkpoint-json-length` bytes.

Status handling: `2xx` is parsed as above; `408`, `429` (honoring `Retry-After`), and `5xx` are retryable and spend the run's retry budget; `401` and `403` are terminal provider configuration failures, because the bridge refuses the service itself and every address would fail the same way; any other `4xx`, including `404` for an address the bridge does not know, malformed JSON or bytes that are not JSON text, a `null` element in `events`, and an oversized body are terminal provider data invalid for that address. A redirect (`3xx`) is not followed: it is a terminal provider configuration failure, because the operator configures `base-url` and retries cannot fix it. Only the retryable class and configuration failures turn the `httpChainProvider` health indicator `DOWN`.

Healthy limits such as page count, event count, run duration, or a busy cursor lease requeue the run as a continuation and do not increment `failure_attempts`. A full provider pool (`SyncCapacityExceededException`) is the service's own capacity, not a provider failure: the run is requeued with backoff by claim count and does not increment `failure_attempts` either. Retryable provider failures, including 429 throttling, increment `failure_attempts`. Provider configuration failures (`ProviderConfigurationException`: rejected credentials, a chain without a provider network mapping, a fetch the configured provider cannot serve) are terminal like malformed pages, so they never burn the retry budget on attempts that cannot succeed.

### Alchemy Provider Page Building

The Alchemy adapter serves one watched address and one ERC-20 registry asset per `fetchObservedEventsPage`:

1. Resolve the network mapping and the enabled asset config; decode `request.cursor` as `{"v":1,"p":"alchemy","nextBlock":N}` (any other shape is provider-data invalid, never a restart from genesis).
2. `eth_blockNumber`, then the finality frontier: `eth_getBlockByNumber("safe"|"finalized", false)` or latest minus `finality-depth-fallback` (`depth` mode, or fallback when the tag is unavailable, flagged in the checkpoint).
3. Start block: the cursor, else the frontier plus one under `registration-safe` (no backfill; the first sync, not the registration, fixes the start, so that sync is idle) or the configured per-chain block under `configured-block`; a stored event high-water at or above the start moves it to the next block and is flagged as `highWaterAdjusted`. A start above the frontier returns an idle page with the same cursor and fresh heights.
4. Scan at most `max-window-blocks` up to the frontier: one `alchemy_getAssetTransfers` call per direction (`toAddress` for `INBOUND`, `fromAddress` for `OUTBOUND`, `category=["erc20"]`, the registry contract, `order=asc`, `maxCount=1000`). A range answered without `pageKey` is complete for every block in it. A paged range is not trusted across pages, but its first page still shows the last block it reached, and ascending order means every block before that boundary was covered in full: the scan narrows the range to those blocks and re-queries them as a complete range while the RPC budget leaves room for it, then drains the boundary block alone with `fromBlock == toBlock`, following `pageKey` in memory with loop, restart, and window checks, and continues with the rest of the window.
5. Merge both streams by `uniqueId`, skip self-transfers, which move no funds and are deliberately not recorded, and rows of another contract (both counted in the checkpoint), map `uniqueId` `:log:{n}` to `eventIndex`, `rawContract.value` and registry decimals to `amount`, `latest - blockHeight + 1` to `confirmations`, emit `SEEN` and let the confirmation policy promote it, and sort by `(blockHeight, eventIndex, txHash)`. A block is never read again, so these confirmations are final: a chain's `required_confirmations` above `latest - frontier + 1` would leave its events `SEEN`.
6. Emit whole blocks only, up to `request.limit`; the first block that does not fit becomes `nextBlock`, and one block with more events than the limit is a terminal `ProviderConfigurationException` because the page contract cannot split a block.

A configuration gap of one address, such as its chain without a network mapping or, under `configured-block`, without a start block, its asset without an enabled config, or a block with more events than a page, is an `AddressConfigurationException`: terminal for that address's runs and shown as the provider's `lastDataError` until the next successful fetch, while health stays `UP`. Registration and re-enabling refuse a chain the provider cannot serve in the first place (`ChainProviderPort.supportsChain`), so only an address registered before the switch, or one whose asset config was disabled since, gets there; the startup preflight refuses both at the next start.

Durable progress moves only past fully drained blocks: `nextCursor` is always a block boundary, `hasMore` is `nextBlock <= safe`, and a block that cannot be finished within `max-rpc-calls-per-fetch` and the provider timeout is left for the next fetch, or reported as a retryable `ChainProviderUnavailableException` when nothing was finished. `pageKey` never reaches `provider_cursor` or `checkpoint`. The checkpoint metadata stays under 1 KiB: provider, chain, network, asset, contract, scan mode and counters, `nextBlock`, latest and safe heights, finality mode and fallback flag, `initialStartBlock`, and skip counters.

### Manual Account Sync Traversal

```mermaid
sequenceDiagram
    participant Worker as SyncRunWorkerJob
    participant Repo as WatchedAddressRepository
    participant Cursor as SyncCursorRepository
    participant Provider as ActiveChainProvider
    participant DB as PostgreSQL

    Worker->>Repo: count active account addresses (cap check)
    Worker->>DB: read run_checkpoint.accountPass
    loop scan after the keyset (created_at, id)
        Worker->>Repo: fetch next active addresses after the keyset
        alt cursor lease acquired
            Worker->>Cursor: acquire address cursor lease
            Worker->>Provider: fetch bounded page(s)
            Worker->>Cursor: checkpoint after page ingest
        else cursor lease busy
            Worker->>Worker: defer the address to the revisit list
        else terminal address failure
            Worker->>Worker: record the address and its error
        end
        Worker->>Worker: move the keyset past the address
    end
    loop revisit deferred addresses once per claim
        Worker->>Cursor: acquire address cursor lease
        Worker->>Provider: fetch bounded page(s)
    end
    Worker->>DB: persist accountPass on continuation, or finish the run
```

Account sync does not own a provider cursor; provider resume state remains per watched address in `sync_cursors`. The run checkpoint holds only the pass: the keyset of the last address the scan finished, whether the scan is complete, the number of addresses visited, the addresses deferred because their lease was busy (at most 100), and the addresses that failed terminally (the count and the first 20 with their errors), well below the 16 KiB limit of `run_checkpoint`. The claim budget (pages, events, duration) ends a claim between addresses; an address with pages left ends the claim without moving the keyset, so the next claim drains it first. The run completes when the scan is complete and no revisit is pending: `SUCCEEDED`, or `FAILED` with the failed addresses in `last_error`. A run queued by an earlier version carries no pass and starts a fresh one, which only replays idempotent cursors.

### Observed Event Ingestion

```mermaid
sequenceDiagram
    participant Client
    participant API as ObservedEventController
    participant Ingest as ObservedTransactionIngestionService
    participant Domain as TransactionStateMachine
    participant Repo as jOOQ Repositories
    participant DB as PostgreSQL

    Client->>API: POST /api/v1/observed-events
    API->>Ingest: ingest(command)
    Ingest->>Repo: find by natural key FOR UPDATE
    Repo->>DB: SELECT observed_transactions ... FOR UPDATE
    DB-->>Repo: existing row or none
    Repo-->>Ingest: current transaction state
    Ingest->>Domain: evaluate(current, incoming, chainConfig)
    Domain-->>Ingest: Created/Updated/NoChange/Conflict
    Ingest->>Repo: insert/update transaction
    Repo->>DB: INSERT/UPDATE observed_transactions
    Ingest->>Repo: insert outbox event if state changed
    Repo->>DB: INSERT outbox_events ON CONFLICT DO NOTHING
    Ingest-->>API: IngestionResponse
    API-->>Client: 200 OK or 201 Created
```

### Confirmations Update To CONFIRMED

```mermaid
sequenceDiagram
    participant Provider
    participant Ingest as ObservedTransactionIngestionService
    participant Domain as TransactionStateMachine
    participant DB as PostgreSQL

    Provider->>Ingest: event status=SEEN confirmations=3
    Ingest->>DB: load observed transaction FOR UPDATE
    Ingest->>DB: load chain_config required_confirmations=3
    Ingest->>Domain: evaluate(SEEN, confirmations=3)
    Domain-->>Ingest: transition SEEN -> CONFIRMED
    Ingest->>DB: update status=CONFIRMED, confirmed_at=now
    Ingest->>DB: insert TRANSACTION_CONFIRMED outbox event
```

### Reorg To REVERTED

```mermaid
sequenceDiagram
    participant Provider
    participant Ingest as ObservedTransactionIngestionService
    participant Domain as TransactionStateMachine
    participant DB as PostgreSQL

    Provider->>Ingest: event status=REVERTED
    Ingest->>DB: load observed transaction FOR UPDATE
    Ingest->>Domain: evaluate(CONFIRMED, REVERTED)
    Domain-->>Ingest: transition CONFIRMED -> REVERTED
    Ingest->>DB: update status=REVERTED, reverted_at=now
    Ingest->>DB: insert TRANSACTION_REVERTED outbox event
```

### Outbox Poller Publishing

```mermaid
sequenceDiagram
    participant Poller as OutboxPublisherJob
    participant DB as PostgreSQL
    participant Publisher as LocalPublisherAdapter

    Poller->>DB: SELECT due NEW/FAILED rows FOR UPDATE SKIP LOCKED
    Poller->>DB: set next_attempt_at = leaseUntil and commit
    DB-->>Poller: leased event batch
    loop each outbox event
        Poller->>Publisher: publish(payload)
        alt publish succeeds
            Poller->>DB: CAS mark PUBLISHED where next_attempt_at = claimedLeaseUntil
        else publish succeeds but completion update fails
            Poller-->>Poller: log completion failure; leave leased NEW/FAILED row for retry
        else publish fails
            Poller->>DB: CAS mark FAILED or DEAD where next_attempt_at = claimedLeaseUntil
        end
    end
```

### Duplicate Event No-Op Path

```mermaid
sequenceDiagram
    participant Provider
    participant Ingest as ObservedTransactionIngestionService
    participant Domain as TransactionStateMachine
    participant DB as PostgreSQL

    Provider->>Ingest: duplicate event
    Ingest->>DB: load existing row FOR UPDATE
    Ingest->>Domain: evaluate(existing, duplicate)
    Domain-->>Ingest: NoChange
    Ingest-->>Provider: duplicate/no-op result
```

No outbox event is created on no-op duplicate processing.

## 7. Kotlin/Spring Package Structure

```text
com.example.assetsync
  api
    controller
    dto
    error
  application
    account
    sync
    transaction
    outbox
  domain
    model
    policy
    state
  infrastructure
    persistence
    provider
    outbox
    observability
  config
```

Package rules:

- Controllers do not know jOOQ.
- Repositories do not accept API DTOs.
- The domain state machine is Spring-independent.
- Application services depend on ports and repositories, not concrete HTTP or database details.
- Infrastructure implements outbound ports.
- API DTOs are mapped into application commands.

## 8. Domain Model And State Machines

### Entities And Concepts

Account:
- Logical grouping for watched addresses.
- Does not imply custody or signing capability.

WatchedAddress:
- Public address registered for observation.
- Belongs to an account.
- Scoped by `chainId`, `address`, and `asset`.

ObservedTransaction:
- Canonical stored representation of an observed transaction event for a watched address and asset.
- Identified idempotently by `chainId + txHash + eventIndex + address + asset`.

ChainConfig:
- Per-chain configuration.
- Contains the confirmation threshold used by the state machine.

AssetConfig:
- Per-chain asset registry row keyed by `(chainId, asset)`: token standard, canonical contract address, decimals, and enabled flag.
- Watched-address registration requires an enabled row; provider adapters resolve the public asset code through it.

OutboxEvent:
- Durable integration event created inside the same database transaction as the observed transaction change.

SyncRun:
- Durable queue and diagnostic record for manual or scheduled sync execution.
- Not a source of truth for transaction state.

### Enums

`TransactionStatus`:
- `SEEN`
- `CONFIRMED`
- `REVERTED`

`Direction`:
- `INBOUND`
- `OUTBOUND`

`OutboxStatus`:
- `NEW`
- `PUBLISHED`
- `FAILED`
- `DEAD`

`OutboxEventType`:
- `TRANSACTION_SEEN`
- `TRANSACTION_CONFIRMED`
- `TRANSACTION_REVERTED`

### Transaction State Transitions

```text
NONE -> SEEN
NONE -> CONFIRMED
NONE -> REVERTED

SEEN -> SEEN       when confirmations increase below threshold
SEEN -> CONFIRMED  when confirmations reach threshold or provider status is CONFIRMED
SEEN -> REVERTED   when provider status is REVERTED

CONFIRMED -> CONFIRMED when confirmations increase
CONFIRMED -> REVERTED  on reorg

REVERTED -> REVERTED duplicate/no-op
```

`REVERTED` is terminal in the MVP. If a previously reverted transaction appears again, the event is treated as a duplicate or conflicting provider input depending on whether immutable fields match.

## 9. PostgreSQL Schema Draft

### `accounts`

Key columns:
- `id uuid primary key`
- `external_ref text null`
- `status text not null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Constraints and indexes:
- `primary key (id)`
- `unique (external_ref)` where `external_ref is not null`
- `check (status in ('ACTIVE', 'DISABLED'))`

Rationale:
- `external_ref` allows callers to map an account to an external system without making it mandatory.
- `status` supports disabling observation without deleting historical data.

### `chain_configs`

Key columns:
- `chain_id text primary key`
- `display_name text not null`
- `required_confirmations integer not null`
- `enabled boolean not null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Constraints and indexes:
- `primary key (chain_id)`
- `check (required_confirmations >= 0)`

Rationale:
- Confirmation thresholds are configuration, not code constants.
- The table is seeded by Liquibase for local chains supported by the MVP.

### `asset_configs`

Key columns:
- `chain_id text not null references chain_configs(chain_id)`
- `asset text not null`
- `token_standard text not null`
- `contract_address text not null`
- `decimals integer not null`
- `display_name text null`
- `enabled boolean not null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Constraints and indexes:
- `primary key (chain_id, asset)`
- `unique (chain_id, contract_address)`
- upper-case asset, lower-case hex contract address, `ERC20` token standard, decimals `0..18`

Rationale:
- The public API keeps `asset` as a code such as `USDC`; the registry is the single place that maps it to a token identity per chain.
- Registration is rejected for assets that are unknown or disabled, so a provider adapter never has to guess a contract address.

### `watched_addresses`

Key columns:
- `id uuid primary key`
- `account_id uuid not null references accounts(id)`
- `chain_id text not null references chain_configs(chain_id)`
- `address text not null`
- `asset text not null`
- `label text null`
- `status text not null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Constraints and indexes:
- `unique (chain_id, address, asset)`
- `index (account_id)`
- `index (chain_id, address)`
- `check (status in ('ACTIVE', 'DISABLED'))`

Rationale:
- The unique constraint prevents duplicate observation registrations for the same chain, address, and asset.
- The account index supports account-level sync and list queries.
- The chain/address index supports provider lookup and diagnostics.

### `observed_transactions`

Key columns:
- `id uuid primary key`
- `chain_id text not null`
- `tx_hash text not null`
- `event_index integer not null`
- `watched_address_id uuid not null references watched_addresses(id)`
- `address text not null`
- `asset text not null`
- `direction text not null`
- `amount numeric(38, 18) not null`
- `block_height bigint not null`
- `confirmations integer not null`
- `status text not null`
- `first_seen_at timestamptz not null`
- `last_seen_at timestamptz not null`
- `confirmed_at timestamptz null`
- `reverted_at timestamptz null`
- `version bigint not null default 0`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`
- `source text null` (who made the last lifecycle change: `rest:<user>` or `provider:<type>`; `demo:seed` for the `demo` dataset)

Constraints and indexes:
- `unique (chain_id, tx_hash, event_index, address, asset)`
- `index (watched_address_id, status)`
- `index (chain_id, tx_hash)`
- `index (chain_id, block_height)`
- `check (event_index >= 0)`
- `check (amount >= 0)`
- `check (confirmations >= 0)`
- `check (direction in ('INBOUND', 'OUTBOUND'))`
- `check (status in ('SEEN', 'CONFIRMED', 'REVERTED'))`

Rationale:
- The natural unique key is the primary idempotency guard.
- `version` supports optimistic diagnostics and can be used for future optimistic locking.
- `first_seen_at`, `last_seen_at`, `confirmed_at`, and `reverted_at` preserve lifecycle timestamps.
- The status index supports operational inspection and future projection jobs.

### `outbox_events`

Key columns:
- `id uuid primary key`
- `aggregate_type text not null`
- `aggregate_id uuid not null`
- `event_type text not null`
- `idempotency_key text not null`
- `payload jsonb not null`
- `status text not null`
- `attempts integer not null default 0`
- `next_attempt_at timestamptz not null`
- `published_at timestamptz null`
- `last_error text null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Constraints and indexes:
- `unique (idempotency_key)`
- `index (status, next_attempt_at)`
- partial due index on `(next_attempt_at, created_at, id) where status in ('NEW', 'FAILED')`
- partial retention index on `(published_at, id) where status = 'PUBLISHED'`
- `index (aggregate_type, aggregate_id)`
- `check (status in ('NEW', 'PUBLISHED', 'FAILED', 'DEAD'))`
- `check (attempts >= 0)`
- `check (event_type in ('TRANSACTION_SEEN', 'TRANSACTION_CONFIRMED', 'TRANSACTION_REVERTED'))`

Rationale:
- The unique idempotency key prevents duplicate lifecycle events.
- `next_attempt_at` is both the retry schedule and the active processing lease token.
- The partial due and retention indexes keep poller and cleanup queries focused on active rows.
- JSONB payload keeps the outbox schema stable while event shapes evolve.

### `sync_runs`

Key columns:
- `id uuid primary key`
- `target_type text not null`
- `target_id uuid not null`
- `status text not null`
- `queued_at timestamptz not null`
- `started_at timestamptz null`
- `finished_at timestamptz null`
- `events_seen integer not null default 0`
- `events_changed integer not null default 0`
- `last_error text null`
- `attempts integer not null default 0`
- `failure_attempts integer not null default 0`
- `continuation_count integer not null default 0`
- `run_checkpoint jsonb not null default '{}'::jsonb`
- `last_requeue_reason text null`
- `next_attempt_at timestamptz not null`
- `locked_by varchar(200) null`
- `lock_token uuid null`
- `locked_until timestamptz null`
- `heartbeat_at timestamptz null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Constraints and indexes:
- `index (target_type, target_id, started_at desc)`
- `index (status, started_at desc)`
- partial due index on `(next_attempt_at, queued_at, id) where status = 'QUEUED'`
- partial expired-running index on `(locked_until, started_at, id) where status = 'RUNNING'`
- partial unique in-flight target index on `(target_type, target_id) where status in ('QUEUED','RUNNING')`
- `check (target_type in ('ACCOUNT', 'ADDRESS'))`
- `check (status in ('STARTED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))`
- `check (events_seen >= 0)`
- `check (events_changed >= 0)`
- `check (attempts >= 0)`
- `check (failure_attempts >= 0)`
- `check (continuation_count >= 0)`
- `check (jsonb_typeof(run_checkpoint) = 'object')`
- lock fields are required for `RUNNING` and null for non-`RUNNING`

Rationale:
- Sync runs are operational records for troubleshooting, API inspection, and durable worker claiming.
- They are not used as transaction idempotency keys.
- `attempts` counts worker claims, `failure_attempts` controls retry budget, and `continuation_count` tracks healthy page/account continuations.
- `run_checkpoint` stores account traversal metadata, not provider resume state.

### `sync_cursors`

Key columns:
- `watched_address_id uuid primary key references watched_addresses(id) on delete cascade`
- `provider_cursor text null`
- `checkpoint jsonb not null default '{}'::jsonb`
- `last_processed_block_height bigint null`
- `last_processed_event_index integer null`
- `last_finalized_block_height bigint null`
- `version bigint not null default 0`
- `locked_by varchar(200) null`
- `lock_token uuid null`
- `locked_until timestamptz null`
- `cursor_updated_at timestamptz null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

Rationale:
- Per-address cursor leases prevent direct address sync and account sync from advancing the same checkpoint concurrently.
- Checkpoint state advances only after a full provider page has been ingested.
- A final empty provider page may omit `nextCursor` only when it supplies durable block high-water such as `safeBlockHeight` or `latestBlockHeight`; empty pages with no cursor and no high-water are rejected as no-progress provider data.
- `version + locked_by + lock_token` fencing makes stale owners harmless.

### Liquibase Changelog Structure

```text
src/main/resources/db/changelog
  db.changelog-master.yaml
  changes/
    <NNN>-<change>.yaml   one changeset per file, included by the master changelog in order
```

The full changeset list, including upgrade preconditions and operator runbooks per changeset, is maintained in `docs/database.md` and is not duplicated here.

Text columns plus CHECK constraints are preferred over PostgreSQL enum types in the MVP. They keep migrations simpler when statuses evolve, while still giving the database enough validation to reject invalid values.

## 10. API Design

All endpoints are under `/api/v1`.

### Endpoints

- `POST /api/v1/accounts`
- `GET /api/v1/accounts/{accountId}`
- `POST /api/v1/accounts/{accountId}/addresses`
- `GET /api/v1/accounts/{accountId}/addresses`
- `PATCH /api/v1/addresses/{addressId}`
- `POST /api/v1/addresses/{addressId}/sync`
- `POST /api/v1/accounts/{accountId}/sync`
- `POST /api/v1/observed-events`
- `GET /api/v1/sync-runs/{syncRunId}`

Deferred read endpoints:

- `GET /api/v1/transactions`
- `GET /api/v1/transactions/{transactionId}`

### Create Account

Request:

```json
{
  "externalRef": "customer-123"
}
```

Response:

```json
{
  "id": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
  "externalRef": "customer-123",
  "status": "ACTIVE",
  "createdAt": "2026-06-19T00:00:00Z"
}
```

### Register Watched Address

Request:

```json
{
  "chainId": "local-evm",
  "address": "0xabc123",
  "asset": "USDC",
  "label": "primary settlement address"
}
```

Response:

```json
{
  "id": "6df29db1-96d2-4665-8945-266c7f90138e",
  "accountId": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
  "chainId": "local-evm",
  "address": "0xabc123",
  "asset": "USDC",
  "status": "ACTIVE",
  "createdAt": "2026-06-19T00:00:00Z"
}
```

### Start Address Sync

Response:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/sync-runs/067bdcd7-23c9-44c5-ac73-caeef65ca5ab
```

```json
{
  "id": "067bdcd7-23c9-44c5-ac73-caeef65ca5ab",
  "targetType": "ADDRESS",
  "targetId": "6df29db1-96d2-4665-8945-266c7f90138e",
  "status": "QUEUED",
  "eventsSeen": 0,
  "eventsChanged": 0,
  "lastError": null,
  "queuedAt": "2026-06-19T00:00:00Z",
  "startedAt": null,
  "finishedAt": null,
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:00Z"
}
```

### Ingest Observed Event

Request:

```json
{
  "chainId": "local-evm",
  "txHash": "0xdeadbeef",
  "eventIndex": 0,
  "address": "0xabc123",
  "asset": "USDC",
  "amount": "12.340000000000000000",
  "blockHeight": 9123456,
  "confirmations": 1,
  "direction": "INBOUND",
  "status": "SEEN"
}
```

Response:

```json
{
  "transactionId": "5e1c9c94-6e36-4fb9-bb27-67800e88ac51",
  "result": "CREATED",
  "status": "SEEN",
  "outboxEvents": ["TRANSACTION_SEEN"]
}
```

Duplicate no-op response:

```json
{
  "transactionId": "5e1c9c94-6e36-4fb9-bb27-67800e88ac51",
  "result": "NO_CHANGE",
  "status": "SEEN",
  "outboxEvents": []
}
```

### Error Cases

- `400 Bad Request`: invalid request shape, invalid amount, invalid enum value, negative confirmation count, or watched-address pagination outside the supported bounds.
- `401 Unauthorized`: missing or invalid HTTP Basic credentials in protected profiles; the response keeps the `WWW-Authenticate: Basic` challenge.
- `403 Forbidden`: authenticated caller without the required role in protected profiles.
- `404 Not Found`: account, watched address, unsupported chain or asset, sync run, or route not found.
- `405 Method Not Allowed`: unsupported HTTP method for a known route, with an `Allow` header.
- `409 Conflict`: duplicate account `externalRef`, duplicate watched address, or immutable observed transaction field mismatch.
- `415 Unsupported Media Type`: request body content type other than JSON.
- `429 Too Many Requests`: the soft cap on queued plus running sync runs is reached; `Retry-After` carries the worker claim interval in whole seconds.
- `500 Internal Server Error`: unexpected failure; the response carries a generic detail and the exception goes to the log.
- `503 Service Unavailable`: request-time infrastructure failure, such as PostgreSQL unavailable, including a request whose HTTP Basic credentials could not be checked; see `docs/failure-modes.md` section 6.

Provider timeout or unavailability during async sync worker execution does not change the already-returned `202 Accepted` POST response. The worker records retry or terminal `FAILED` state on the `sync_run`, and clients inspect it through `GET /api/v1/sync-runs/{id}`.

ProblemDetail example:

```json
{
  "type": "https://asset-sync-service/errors/immutable-field-conflict",
  "title": "Immutable observed transaction field conflict",
  "status": 409,
  "detail": "Observed transaction natural key matched an existing row, but immutable fields did not match.",
  "instance": "/api/v1/observed-events",
  "requestId": "018ff4c8-4b6f-7f2e-a3aa-0c7d23f6ac4e",
  "chainId": "local-evm",
  "txHash": "0xdeadbeef",
  "eventIndex": 0,
  "address": "0xabc",
  "asset": "USDC",
  "conflictingFields": ["AMOUNT"]
}
```

## 11. Idempotency Design

### Observed Transaction Idempotency

The natural idempotency key is:

```text
chainId + txHash + eventIndex + address + asset
```

This key is enforced by a PostgreSQL unique constraint on `observed_transactions`.

Processing behavior:
- If no row exists, create the observed transaction and create the matching lifecycle outbox event.
- If a row exists, lock it, run transition logic, and update only when a meaningful change exists.
- If the incoming event is a duplicate of the stored state, return `NO_CHANGE`.
- If immutable fields conflict, reject the event with `409 Conflict`.

Immutable fields:
- `chainId`
- `txHash`
- `eventIndex`
- `address`
- `asset`
- `direction`
- `amount`

Mutable fields:
- `blockHeight`
- `confirmations`
- `status`
- `lastSeenAt`
- `confirmedAt`
- `revertedAt`

`blockHeight` is mutable to tolerate provider corrections before final confirmation. A conflicting lower or unrelated block height should be logged and handled according to transition policy.

### Sync Idempotency

Sync operations can be retried safely because each observed transaction event is idempotent. `sync_runs` records are diagnostic and do not define business idempotency.

Sync flow:
- POST creates or reuses a `QUEUED` `sync_run` and returns `202 Accepted`.
- A scheduled worker claims due rows with `FOR UPDATE SKIP LOCKED`, marks them `RUNNING`, and sets a lease token.
- Call provider outside a database transaction and outside the request thread.
- Ingest each event transactionally.
- Fenced completion marks the run `SUCCEEDED`, `FAILED`, or `QUEUED` for retry.

### Reorg Idempotency

`REVERTED` is terminal in the MVP. Duplicate reorg events do not create additional database changes or outbox events.

### Outbox Idempotency

Outbox events have a unique `idempotency_key`:

```text
observed-tx:{transactionId}:status:{newStatus}:v:{version}
```

Example:

```text
observed-tx:5e1c9c94-6e36-4fb9-bb27-67800e88ac51:status:CONFIRMED:v:2
```

This prevents duplicate `TRANSACTION_SEEN`, `TRANSACTION_CONFIRMED`, and `TRANSACTION_REVERTED` events for the same transaction lifecycle stage. The transaction id is a UUID, so no free-text part of the natural key, such as a hash or address containing `:`, can make two transactions share a key. Earlier versions joined the natural key into the key; their rows keep those keys, and no key of the current format can equal one.

## 12. Confirmation And Reorg Design

Confirmation thresholds come from `chain_configs.required_confirmations`.

Rules:
- Confirmation count is monotonic. Older lower counts do not reduce stored confirmations.
- `SEEN` becomes `CONFIRMED` when `confirmations >= required_confirmations`.
- Provider status `CONFIRMED` also moves the transaction to `CONFIRMED`.
- Older `SEEN` events after `CONFIRMED` are treated as stale and ignored.
- `REVERTED` wins over every other status.
- `CONFIRMED -> REVERTED` is allowed to model reorg after confirmation.
- Duplicate `REVERTED` events are no-ops.
- Conflicting immutable input is rejected as provider conflict.

When provider input is reordered, the service keeps the highest reliable lifecycle state according to the state machine. The exception is `REVERTED`, which is a terminal reorg signal.

## 13. Outbox Design

The service uses the transactional outbox pattern. When an observed transaction is created or its lifecycle state changes, the corresponding outbox event is inserted in the same database transaction.

Outbox event types:
- `TRANSACTION_SEEN`
- `TRANSACTION_CONFIRMED`
- `TRANSACTION_REVERTED`

Payload fields:
- `eventId`
- `eventType`
- `occurredAt`
- `transactionId`
- `chainId`
- `txHash`
- `eventIndex`
- `address`
- `asset`
- `amount`
- `direction`
- `status`
- `confirmations`
- `blockHeight`
- `source`: who caused this lifecycle change, `rest:<user>` for `POST /api/v1/observed-events` or `provider:<http|alchemy|fake>` for a sync (`demo:seed` for the events the `demo` profile seeds), so a status reported through the API stays distinguishable from provider data downstream. The published log line carries it too.

Poller behavior:
- Scheduled job selects due `NEW` or `FAILED` rows with `FOR UPDATE SKIP LOCKED`.
- Claiming immediately moves `next_attempt_at` to `now + processing lease`, then commits before publishing.
- Successful publish marks the row `PUBLISHED` and sets `published_at` only when the row still has the claimed lease value.
- Failed publish increments `attempts`, stores `last_error`, and sets `FAILED` with bounded backoff or terminal `DEAD` at max attempts, again fenced by the claimed lease value.
- A completion failure after successful publish is logged and metered separately. It does not increment attempts, does not update `last_error`, and cannot move the row to `DEAD`; the leased row becomes retryable after the lease expires.
- A stale completion update that affects zero rows is ignored because another poller owns or has already completed the row.
- Retention can delete old `PUBLISHED` rows in bounded batches.

The MVP publisher uses a local adapter, for example structured logs. Kafka or SQS can be added later by replacing the publisher adapter while preserving the outbox table and poller semantics.

Delivery semantics are at-least-once. A publish can happen twice if the process crashes after publishing but before marking the event as `PUBLISHED`. Downstream consumers must deduplicate by `eventId` or `idempotencyKey`.

## 14. Transaction Boundaries

Use Spring transactions for:
- Account creation.
- Watched address registration.
- Single observed event ingestion.
- Sync run creation and final status update.
- Outbox poll batch claiming.
- Per-event outbox completion updates.

Do not keep a database transaction open while calling the chain provider.

Recommended sync sequence:

```text
1. Insert sync_run with status QUEUED in a short transaction, or return an existing QUEUED/RUNNING run for the same target.
2. Worker claims due QUEUED rows with FOR UPDATE SKIP LOCKED and writes RUNNING lease fields.
3. Worker calls the active chain provider outside a database transaction.
4. Ingest each event in its own transaction.
5. Fenced completion updates sync_run to SUCCEEDED, FAILED, or QUEUED retry.
```

Concurrency controls:
- Unique constraints protect natural keys.
- Existing observed transaction rows are loaded `FOR UPDATE` before transition.
- Outbox unique idempotency keys prevent duplicate lifecycle events.
- `READ COMMITTED` is sufficient because row locks and unique constraints guard the critical races.
- Optional future extension: PostgreSQL advisory lock per address or account to reduce duplicate provider work across concurrent syncs.

## 15. Kotlin/Spring Implementation Notes

Use Kotlin data classes for:
- API request and response DTOs.
- Application commands.
- Domain snapshots.
- Transition results.

jOOQ avoids common JPA entity pitfalls:
- final Kotlin classes and proxies
- data class equality with mutable persistence state
- lazy-loading surprises
- no-arg constructor requirements
- accidental persistence changes through entity graphs

Use enums for stable finite sets:
- `TransactionStatus`
- `Direction`
- `OutboxStatus`
- `OutboxEventType`

Use sealed classes for outcomes:
- `Created`
- `Updated`
- `NoChange`
- `Conflict`

Nullable fields:
- `externalRef`
- `label`
- `confirmedAt`
- `revertedAt`
- `finishedAt`
- `lastError`
- `publishedAt`

Non-null fields:
- ids
- `chainId`
- `address`
- `asset`
- `txHash`
- `eventIndex`
- `amount`
- `blockHeight`
- `confirmations`
- status fields
- timestamps required for lifecycle tracking

No coroutines or WebFlux are used in the MVP. Blocking Spring MVC with jOOQ, HikariCP, and PostgreSQL is simpler and adequate for the service scope.

Spring-specific notes:
- Use `kotlin("plugin.spring")` or all-open configuration for Spring-managed classes.
- Avoid `@Transactional` self-invocation. Put transactional ingestion methods in a separate Spring bean called by sync orchestration.
- Keep scheduled publisher operations idempotent because jobs can rerun after crashes.

## 16. Failure Modes

Duplicate observed event:
- Handled by natural unique key and no-op transition.

Provider sends older confirmation count:
- Stored confirmations remain monotonic.

Provider sends conflicting immutable fields:
- Reject with `409 Conflict` and log structured diagnostics.

Provider page out of checkpoint order or behind the checkpoint:
- Reject the whole page as terminal provider data invalid; ingest nothing from it and leave `sync_cursors` unchanged.

Stale `SEEN` after `CONFIRMED`:
- Treat as no-op stale event.

Reorg after confirmed:
- Transition `CONFIRMED -> REVERTED`.
- Create `TRANSACTION_REVERTED` outbox event once.

DB commit succeeds but publisher fails:
- Outbox event remains `NEW` or `FAILED` and is retried.

Publisher publishes twice:
- Accepted by at-least-once delivery semantics.
- Downstream consumers deduplicate by event id or idempotency key.

Concurrent sync for same address:
- Duplicate POSTs for the exact same address or account return the existing in-flight run.
- Duplicate provider work is still possible after lease recovery or process crash, but event ingestion remains safe.
- Optional advisory lock can be added later for stricter global admission control.

PostgreSQL unavailable:
- API returns `503` with `database-unavailable`, also for a request whose credentials cannot be checked; timeouts and limits are in `docs/failure-modes.md` section 6.
- Readiness health check fails.
- No fake success response is returned.

Provider timeout:
- Worker stores concise failure detail and requeues with backoff until max attempts.
- At max attempts the run is marked `FAILED`.
- Already ingested committed events remain valid.
- POST already returned `202 Accepted`; clients observe the outcome through `GET /api/v1/sync-runs/{id}`.

Process shutdown during sync:
- The worker stops claiming, drains in-flight runs up to `worker.shutdown-timeout`, then interrupts the rest.
- Interrupted runs return to `QUEUED` without consuming `failure_attempts`; already committed page events remain valid.

App crashes after DB commit before publish:
- Outbox poller resumes after restart and publishes pending events.

## 17. Testing Strategy

Unit tests:
- Transaction state transitions.
- Confirmation threshold logic.
- Stale event handling.
- Reorg handling.
- Immutable conflict detection.
- Outbox idempotency key generation.

Integration tests with Testcontainers PostgreSQL:
- Liquibase migration test.
- Account and watched address constraint tests.
- Idempotent observed transaction insert test.
- Duplicate event creates one observed transaction and one outbox event.
- Confirmation transition creates `TRANSACTION_CONFIRMED`.
- Reorg after confirmed creates `TRANSACTION_REVERTED`.
- Duplicate reorg does not create duplicate outbox events.
- Rollback test proves transaction and outbox write are atomic.
- Concurrent duplicate processing test.
- Outbox poller retry test.
- `FOR UPDATE SKIP LOCKED` behavior test.

API tests:
- Request validation.
- Error mapping to `ProblemDetail`.
- Duplicate watched address conflict.
- Observed event conflict response.
- OpenAPI endpoint availability.

## 18. Observability

Logs:
- Use structured log messages in production-like configuration.
- `X-Request-Id` is echoed to clients, attached to `ProblemDetail`, stored in MDC for request logs, and copied into sync provider executor tasks. Executor threads restore their previous MDC state after each task to avoid leaking request ids between syncs.
- Include correlation and domain fields where available:
  - `syncRunId`
  - `accountId`
  - `addressId`
  - `chainId`
  - `txHash`
  - `eventIndex`
  - `transition`
  - `outboxEventId`

Metrics, as registered by `AssetSyncMetrics`:
- `asset.sync.observed.events.ingested{result,status}`: counter per ingestion outcome (`CREATED`, `UPDATED`, `NO_CHANGE`, `CONFLICT`) and resulting status.
- `asset.sync.observed.transaction.transitions{eventType,status}`: counter of lifecycle transitions that emitted an outbox event.
- `asset.sync.observed.transaction.immutable.conflicts`: counter of rejected immutable-field conflicts.
- `asset.sync.sync.runs{targetType,status}`: counter of sync run state changes (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`).
- `asset.sync.sync.continuations{reason,targetType}`: counter of healthy requeues (`CONTINUATION`, `LEASE_BUSY`) that do not consume retry budget.
- `asset.sync.provider.fetches{targetType,status}`: counter of provider page fetches (`ATTEMPTED`, `SUCCEEDED`, `FAILED`).
- `asset.sync.provider.fetch.duration{targetType,status}`: timer around one provider page fetch.
- `asset.sync.provider.pages{targetType,result}`: counter of validated pages (`SUCCEEDED`, `FAILED`, `MALFORMED`).
- `asset.sync.provider.page.events{targetType}`: distribution summary of events per provider page.
- `asset.sync.provider.alchemy.rpc{network,method,result}`: counter of Alchemy JSON-RPC calls (`SUCCEEDED`, `UNAVAILABLE`, `INVALID`, `CONFIGURATION`).
- `asset.sync.provider.alchemy.rpc.duration{network,method,result}`: timer around one Alchemy JSON-RPC call.
- `asset.sync.provider.alchemy.block.fallbacks{network}`: counter of blocks the Alchemy adapter drained alone because their window came back paged.
- `asset.sync.provider.alchemy.narrowings{network}`: counter of paged windows the Alchemy adapter narrowed to the blocks before their page boundary.
- `asset.sync.provider.alchemy.skipped.rows{network,reason}`: counter of transfer rows skipped before emission (`SELF_TRANSFER`, `WRONG_TOKEN`, `BELOW_HIGH_WATER`).
- `asset.sync.cursor.leases{result}`: counter of cursor lease operations (`ACQUIRED`, `BUSY`, `EXTENDED`, `LOST`, `RELEASED`, `RELEASE_FAILED`).
- `asset.sync.cursor.checkpoints{result}`: counter of checkpoint advances (`ADVANCED`, `STALE`).
- `asset.sync.outbox.batches{result}`: counter of poller batches (`EMPTY`, `SUCCEEDED`, `FAILED`, `PARTIAL_FAILURE`).
- `asset.sync.outbox.events{eventType,status}`: counter of per-event outcomes (`PUBLISHED`, `FAILED`, `DEAD`, `COMPLETION_FAILED`).
- `asset.sync.outbox.scheduler.ticks{result}`: counter of outbox poller ticks that ended in an exception (`FAILED`).
- `asset.sync.outbox.backlog.total`: gauge of outbox rows in `NEW` or `FAILED`.
- `asset.sync.outbox.dead.total`: gauge of outbox rows in `DEAD`.

Health checks:
- Spring Actuator liveness.
- Spring Actuator readiness.
- PostgreSQL connectivity.
- Provider health indicator follows the selected provider: fake in `local`/`test`, HTTP bridge or Alchemy elsewhere. It turns `DOWN` only on availability failures: a timeout, a transport error, `5xx`, `429`, and for Alchemy an outage during the startup probe (`probe-failed`), and on configuration failures: rejected credentials (`401`, `403`) and a redirect. Both last until the first successful fetch. Invalid data for one address, such as a `4xx` answer or malformed JSON, and under Alchemy a configuration gap of one address keep the state and appear as the `lastDataError` detail until the next successful fetch, so one bad address cannot turn the aggregate health into `503`; `asset.sync.provider.pages` counts pages with invalid data as `MALFORMED` and those with a configuration gap as `FAILED`. The Alchemy indicator reports the provider, the auth mode, the probed networks, and the state, plus the scrubbed error after a failed startup probe or fetch; never an endpoint, a header, or the API key. The HTTP bridge indicator's error names the kind of a transport failure, such as `Provider transport failure: timeout (SocketTimeoutException).`, never the bridge URL; the WARN line `http_provider_page_fetch_failed` adds the cause chain with every URL cut out.
- Component details are shown to authenticated callers (`management.endpoint.health.show-details: when-authorized`) and to everyone in `local`; anonymous probes see only the aggregate status.

Build information:
- `/actuator/info` exposes the build name and version written by the Gradle build (`springBoot.buildInfo`), so a running instance can be matched to a release.

## 19. Docker Compose Services

MVP services:
- `asset-sync-service`
- `postgres`

Both services publish their ports on `127.0.0.1` only. Started on a host instead, `local` and `demo` listen on `127.0.0.1` themselves (`server.address`, overridden by `SERVER_ADDRESS`); compose sets `SERVER_ADDRESS=0.0.0.0` for the application container, which is reached through its published port. `ASSET_SYNC_HTTP_BIND_ADDRESS` widens the application port for a remote demo under a protected profile; PostgreSQL stays on loopback. The application service has a 40-second `stop_grace_period`, longer than the 30-second graceful-shutdown phase.

Metrics are exposed through Actuator. The MVP Docker Compose file does not include Prometheus or Grafana services.

Redis and Kafka are not needed in the MVP. PostgreSQL constraints, row locks, Liquibase migrations, and the transactional outbox provide the required reliability model for the first implementation.

## 20. Repository Structure

```text
asset-sync-service
  build.gradle.kts
  docker-compose.yml
  README.md
  docs
    architecture.md
    api.md
    failure-modes.md
  src
    main
      kotlin
        com
          example
            assetsync
              api
              application
              domain
              infrastructure
              config
      resources
        application.yml
        application-local.yml
        db
          changelog
            db.changelog-master.yaml
            changes
    test
      kotlin
        com
          example
            assetsync
              unit
              integration
```

## 21. MVP Scope And Production Extensions

### MVP Scope

- Account registration.
- Watched address registration.
- Chain provider adapters selected by profile and configuration: fake in `local`/`test`, HTTP bridge or Alchemy elsewhere.
- Manual sync by address and account.
- Observed transaction ingestion API.
- Idempotent transaction processing.
- Confirmation threshold handling.
- Explicit reorg simulation through `REVERTED` events.
- Transactional outbox.
- Scheduled outbox poller with local publisher adapter.
- PostgreSQL schema managed by Liquibase.
- OpenAPI documentation.
- Testcontainers-based integration tests.
- Basic structured logging, metrics, and health checks.

### Deferred Extensions

- Balance projection as an eventually consistent read model.
- Kafka or SQS outbox publisher.
- Debezium CDC-based outbox publishing.
- Provider coverage beyond Alchemy ERC-20 transfers and the generic HTTP page contract.
- Multi-instance sync coordination with advisory locks or a scheduler lock.
- Multi-tenant authorization and account ownership.
- Audit event history.
- Replayable projections.

Balance projection should be implemented later as a rebuildable read model derived from observed transactions and outbox/projection events. It should not become the source of truth for transaction lifecycle state.
