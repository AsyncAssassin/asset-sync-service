# Testing Specification

Status: Current MVP test plan  
Scope: MVP test plan for later implementation phases  
Source of truth: `docs/architecture.md`

## 1. Testing Principles

The MVP test suite should prove idempotency, transaction atomicity, state transitions, and outbox reliability before adding real provider integrations.

Principles:

- Keep domain transition tests pure and fast.
- Use Testcontainers PostgreSQL for persistence behavior that depends on constraints, locks, JSONB, and Liquibase.
- Test API behavior through Spring MVC request handling.
- Exercise concurrency with the real database rather than mocks.
- Do not introduce Redis, Kafka, WebFlux, coroutines, or balance projection test fixtures in the MVP.

The repository now contains unit and integration tests that run under Gradle's standard `test` task.

## 2. Unit Tests

Target areas:

- Domain state machine.
- Confirmation threshold policy.
- Immutable field comparison.
- Outbox idempotency key generation.
- Application command validation helpers where not covered by API tests.

Required cases:

| Area | Cases |
| --- | --- |
| New event | `NONE -> SEEN`, `NONE -> CONFIRMED`, `NONE -> REVERTED` |
| Seen lifecycle | `SEEN -> SEEN`, `SEEN -> CONFIRMED`, `SEEN -> REVERTED` |
| Confirmed lifecycle | `CONFIRMED -> CONFIRMED`, `CONFIRMED -> REVERTED` |
| Reverted lifecycle | `REVERTED -> REVERTED`, stale `SEEN` after `REVERTED`, stale `CONFIRMED` after `REVERTED` |
| Confirmations | threshold reached, threshold not reached, threshold `0`, lower stale count ignored |
| Conflicts | amount mismatch, direction mismatch |
| Outbox | status-specific idempotency key, no event on no-op, no event on conflict |

Expectations:

- Domain tests do not start Spring.
- Domain tests do not use jOOQ.
- Time-dependent assertions use an injected clock or explicit timestamps.

## 3. Integration Tests With Testcontainers PostgreSQL

Target areas:

- Liquibase migrations.
- PostgreSQL constraints and indexes.
- jOOQ repository behavior.
- Transaction boundaries.
- Row locking and outbox polling.

Required cases:

| Area | Cases |
| --- | --- |
| Migrations | Full Liquibase changelog applies on an empty PostgreSQL database |
| Accounts | `external_ref` uniqueness, valid statuses, nullable `external_ref` |
| Watched addresses | FK to account, FK to chain config, unique `chain_id + address + asset` |
| Observed transactions | natural key uniqueness, invalid enum checks, non-negative checks |
| First ingest | creates one observed transaction and one matching outbox event |
| Duplicate ingest | creates no second transaction and no second outbox event |
| Confirmation | `SEEN -> CONFIRMED` updates row and creates one `TRANSACTION_CONFIRMED` event |
| Reorg | `CONFIRMED -> REVERTED` updates row and creates one `TRANSACTION_REVERTED` event |
| Duplicate reorg | no duplicate outbox event |
| Atomicity | rollback prevents observed transaction and outbox writes from splitting |
| Retry race | concurrent duplicate processing results in one canonical row |
| Outbox poller | `FOR UPDATE SKIP LOCKED` prevents duplicate claims across pollers |
| Publisher retry | failed publish increments attempts and schedules `next_attempt_at` |

Testcontainers expectations:

- Use the same PostgreSQL major version intended for local Docker Compose.
- Run Liquibase migrations before jOOQ repository tests.
- Avoid replacing database behavior with H2 or in-memory substitutes.

## 4. API Tests

Target areas:

- Spring MVC routing.
- Request validation.
- DTO mapping.
- `ProblemDetail` error mapping.
- HTTP status code behavior.

Required cases:

| Endpoint | Cases |
| --- | --- |
| `POST /api/v1/accounts` | create success, duplicate `externalRef`, blank `externalRef` |
| `GET /api/v1/accounts/{accountId}` | found, not found, invalid UUID |
| `POST /api/v1/accounts/{accountId}/addresses` | create success, account not found, chain disabled/not found, duplicate address, validation failures |
| `GET /api/v1/accounts/{accountId}/addresses` | list success, account not found |
| `POST /api/v1/observed-events` | created, updated, no-change duplicate, immutable conflict, validation failures |
| `POST /api/v1/addresses/{addressId}/sync` | success, address not found, provider timeout |
| `POST /api/v1/accounts/{accountId}/sync` | success, account not found, provider failure |
| `GET /api/v1/sync-runs/{syncRunId}` | found, not found |

ProblemDetail assertions:

- `type` is stable and service-owned.
- `title` is human-readable.
- `status` matches the HTTP status code.
- `detail` is useful but does not expose stack traces.
- Domain identifiers are included for conflict diagnostics where safe.

Deferred API tests, not part of the current implemented MVP:

| Future endpoint | Cases |
| --- | --- |
| `GET /api/v1/transactions` | filter by status/account/address, invalid filter values |
| `GET /api/v1/transactions/{transactionId}` | found, not found, invalid UUID |

## 5. Outbox And Concurrency Tests

Concurrency behavior must be verified against PostgreSQL.

Required tests:

- Two concurrent ingests of the same new observed event produce one row and one lifecycle outbox event.
- Concurrent confirmation updates for the same transaction produce one `TRANSACTION_CONFIRMED` outbox event.
- Concurrent reorg updates for the same transaction produce one `TRANSACTION_REVERTED` outbox event.
- Two poller workers using `FOR UPDATE SKIP LOCKED` claim disjoint outbox rows.
- A failed publish leaves the event eligible for retry only after `next_attempt_at`.
- A process-crash simulation after publish but before marking `PUBLISHED` demonstrates at-least-once semantics by allowing retry.

Implementation note:

- Keep network/provider calls outside database transactions in tests that inspect lock duration.
- Use latches or barriers for deterministic concurrency where needed.

## 6. Gradle Commands

The current Gradle project runs both unit and integration tests under `test`; there is no separate `integrationTest` task.

```bash
./gradlew test
./gradlew check
./gradlew bootRun
```

If jOOQ generation is configured as a separate task:

```bash
./gradlew generateJooq
```

Do not add a markdown or test tool only for the Specs phase.

## 7. Future Extensions

Deferred test areas:

- Real provider adapter contract tests.
- Provider cursor and block-range scan tests.
- Broker publisher tests for Kafka, SQS, or CDC.
- Balance projection rebuild tests.
- Multi-tenant authorization tests.
- Scheduler/advisory-lock tests for multi-instance coordination.
