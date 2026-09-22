# Implementation Plan

Status: Phase 16 complete  
Scope: Reviewable MVP phases  
Source of truth: `docs/architecture.md`

## 1. Phase 1: Specs

Deliverables:

- `docs/api.md`
- `docs/database.md`
- `docs/domain-state-machine.md`
- `docs/testing.md`
- `docs/failure-modes.md`
- `docs/implementation-plan.md`

Verification:

```bash
git status --short
find docs -maxdepth 1 -type f | sort
```

Notes:

- Documentation remains in English.
- No Kotlin/Spring scaffold is created in this phase.
- No commit or push is performed.

## 2. Phase 2: Scaffold

Deliverables:

- Gradle Kotlin project.
- Spring Boot 3.x blocking Spring MVC application.
- Kotlin package structure aligned with architecture:
  - `api`
  - `application`
  - `domain`
  - `infrastructure`
  - `config`
- Local application configuration.
- Docker Compose with application and PostgreSQL only.
- Basic health endpoint through Spring Actuator.

Verification:

```bash
./gradlew check
./gradlew bootRun
docker compose config
```

MVP boundary:

- Do not add Redis, Kafka, WebFlux, coroutines, or balance projection.

## 3. Phase 3: Database

Deliverables:

- Liquibase master changelog.
- Changesets for `accounts`, `chain_configs`, `watched_addresses`, `observed_transactions`, `outbox_events`, and `sync_runs`.
- Seed data for local chain configuration.
- jOOQ generation configuration.
- Repository package skeleton using generated jOOQ types only inside infrastructure.

Verification:

```bash
./gradlew generateJooq
./gradlew test
./gradlew test
```

Review focus:

- Natural unique keys.
- `CHECK` constraints for enum-like fields.
- `numeric(38, 18)` for amounts.
- Indexes for sync and outbox paths.

## 4. Phase 4: Account And Address

Deliverables:

- Account application service.
- Watched address application service.
- REST controllers and DTOs for account/address endpoints.
- jOOQ repositories for accounts, chain configs, and watched addresses.
- ProblemDetail mapping for validation, not found, and duplicate conflicts.

Verification:

```bash
./gradlew test
./gradlew test
./gradlew bootRun
```

Review focus:

- Controllers do not use jOOQ directly.
- Watched address uniqueness is enforced by PostgreSQL.
- API responses match `docs/api.md`.

## 5. Phase 5: Domain Ingestion

Deliverables:

- Spring-independent domain state machine.
- Domain models, enums, and transition result types.
- Confirmation threshold policy.
- Immutable field conflict logic.
- Unit tests covering the full transition table.

Verification:

```bash
./gradlew test
```

Review focus:

- No Spring dependencies in domain transition logic.
- `REVERTED` is terminal in the MVP.
- Stale events are no-ops.

## 6. Phase 6: Observed Events

Deliverables:

- Observed event ingestion application service.
- Observed transaction jOOQ repository.
- `POST /api/v1/observed-events`.
- Transactional insert/update behavior.
- ProblemDetail mapping for immutable conflicts and validation failures.
- Initial outbox row creation hook for lifecycle events.

Verification:

```bash
./gradlew test
./gradlew test
```

Review focus:

- Existing observed rows are loaded `FOR UPDATE`.
- Natural unique key races are handled idempotently.
- Outbox writes happen in the same database transaction as transaction state changes.

## 7. Phase 7: Sync

Deliverables:

- Chain provider port.
- Fake/mock chain provider implementation.
- Sync application service.
- `POST /api/v1/addresses/{addressId}/sync`.
- `POST /api/v1/accounts/{accountId}/sync`.
- `GET /api/v1/sync-runs/{syncRunId}`.
- Sync run repository.

Verification:

```bash
./gradlew test
./gradlew test
./gradlew bootRun
```

Review focus:

- Provider calls happen outside database transactions.
- Each provider event is ingested through the same ingestion path as the API.
- Sync run failure does not roll back already committed event ingestion.

## 8. Phase 8: Outbox

Deliverables:

- Outbox repository.
- Local publisher adapter, for example structured logs.
- Scheduled outbox poller.
- Retry/backoff behavior.
- `FOR UPDATE SKIP LOCKED` batch claiming.
- Integration tests for concurrent pollers and retry behavior.

Verification:

```bash
./gradlew test
./gradlew test
```

Review focus:

- Delivery semantics are at-least-once.
- `idempotency_key` prevents duplicate lifecycle rows.
- Local publisher work is bounded while outbox rows are claimed with `FOR UPDATE SKIP LOCKED`.

## 9. Phase 9: Observability

Deliverables:

- Structured logging fields for sync, transaction, and outbox flows.
- Metrics for observed events, transitions, sync runs, provider requests, and outbox backlog.
- Liveness and readiness health checks.
- PostgreSQL readiness integration.
- Optional fake provider health indicator.

Verification:

```bash
./gradlew test
./gradlew bootRun
```

Manual checks:

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/metrics
```

Review focus:

- Logs include useful domain identifiers without sensitive material.
- Readiness fails when PostgreSQL is unavailable.

## 10. Phase 10: README And Final Verification

Deliverables:

- README with local run instructions.
- API examples.
- Architecture summary linking to detailed docs.
- Docker Compose verification steps.
- Final MVP behavior checklist.

Verification:

```bash
./gradlew clean bootJar
docker compose up --build -d
docker compose config
./gradlew clean test
./gradlew clean check
git ls-files build/generated/sources/jooq/main/kotlin
```

Suggested manual smoke flow:

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"externalRef":"customer-123"}'

curl -s -X POST http://localhost:8080/api/v1/accounts/<account-id>/addresses \
  -H 'Content-Type: application/json' \
  -d '{"chainId":"local-evm","address":"0x742d35Cc6634C0532925a3b844Bc454e4438f44e","asset":"USDC","label":"primary settlement address"}'

curl -s -X POST http://localhost:8080/api/v1/observed-events \
  -H 'Content-Type: application/json' \
  -d '{"chainId":"local-evm","txHash":"0x9f1c2d3e4f5061728394a5b6c7d8e9f00112233445566778899aabbccddeeff0","eventIndex":0,"address":"0x742d35Cc6634C0532925a3b844Bc454e4438f44e","asset":"USDC","amount":"12.340000000000000000","blockHeight":9123456,"confirmations":1,"direction":"INBOUND","status":"SEEN"}'
```

Review focus:

- Documentation and implemented behavior agree.
- No deferred extension is accidentally introduced into MVP.
- Final `git status --short` is understood before handoff.

## 11. Phase 11: Block Height Invariant And Scheduler Isolation

Deliverables:

- Changeset `012` adding `observed_transactions.block_height >= 0` as `NOT VALID`, validated immediately.
- Preflight query and cleanup runbook for legacy negative block heights in `docs/database.md`.
- Scheduled jobs disabled in `test`, `e2e`, and boot-smoke contexts without changing production behavior.

Verification:

```bash
./gradlew clean check
```

Review focus:

- The migration cannot silently rewrite business rows; operators clean legacy data first.
- Integration tests prove the database rejects a negative block height.

## 12. Phase 12: Durable Asynchronous Sync

Deliverables:

- Sync POST endpoints enqueue a durable `sync_runs` row and return `202 Accepted` with a `Location` header.
- Changeset `013` turning `sync_runs` into the queue: `QUEUED` and `RUNNING` statuses, claim lease, `lock_token`, attempts, and a partial unique in-flight index per target.
- `SyncRunWorkerJob` claiming due runs with `FOR UPDATE SKIP LOCKED`, heartbeating leases, and completing with fenced updates.
- Recovery of expired `RUNNING` leases and legacy stale `STARTED` runs with jittered backoff.
- Cumulative `eventsSeen` and `eventsChanged` across retries.

Verification:

```bash
./gradlew clean check
git diff --check
```

Review focus:

- Duplicate POSTs for the same target reuse the in-flight run.
- Stale workers cannot overwrite a run that was re-claimed or completed.
- Execution is at-least-once; ingestion idempotency makes replays safe.

## 13. Phase 13: Provider Pagination And Durable Cursors

Deliverables:

- Page-based `ChainProviderPort` with cursor, limit, `hasMore`, and block high-water fields.
- Changeset `014` adding per-watched-address `sync_cursors` with lease and checkpoint fields, and separating `failure_attempts` from `continuation_count` on `sync_runs`.
- Fenced cursor acquire, extend, advance, and release, with a cursor heartbeat during page work.
- Bounded page, event, duration, and continuation budgets per claim; account traversal fairness across addresses.
- HTTP provider adapter with bounded response bodies and `429` `Retry-After` handling.

Verification:

```bash
./gradlew clean check
```

Review focus:

- A checkpoint advances only after the whole page was ingested.
- Healthy continuations never consume retry budget; provider failures do.
- Pages out of checkpoint order are rejected without advancing the cursor.

## 14. Phase 14: Demo Readiness Polish

Deliverables:

- README section for the `demo` profile: seeded lifecycle dataset, HTTP Basic roles, in-process provider simulator, and a Docker Compose variant through `SPRING_PROFILES_ACTIVE`.
- `ProblemDetail` responses for unknown routes, unsupported methods, unsupported media types, missing parameters, and unexpected failures, using the same service-owned `type` URIs.
- Removal of sync-era dead code that became unreachable once sync turned asynchronous.
- Jackson pinned to a patched 2.21.x line through the `jackson-bom.version` property.

Verification:

```bash
./gradlew clean check
docker compose config
```

Plus a live run of the README quickstart and the `demo` profile, locally and inside the Compose image.

Review focus:

- One `@RestControllerAdvice` keeps every error on the same `type` namespace.
- Security exceptions still reach Spring Security's `ExceptionTranslationFilter`.

## 15. Phase 15: Startup Warnings And Health Details

Deliverables:

- `UserDetailsServiceAutoConfiguration` excluded in `local` and `test`, whose security chains are `permitAll`.
- Explicit `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` flags; both endpoints stay behind HTTP Basic outside `local`.
- Health `show-details: when-authorized`, and `always` in `local`.

Verification:

```bash
./gradlew check
```

Plus a boot of the jar under `local` and `demo` with zero `WARN` lines at startup.

Review focus:

- Protected profiles keep their JDBC user store; anonymous health probes keep the aggregate status only.

## 16. Phase 16: Documentation Sync And Drift Guards

Deliverables:

- Architecture, API, database, failure-mode, and testing documents aligned with the shipped behavior: full metric catalogue, error mapping, page-contract ordering rules, and phase history.
- `DocsConsistencyTests` asserting that every changeset file, meter name, and `ProblemDetail` type in the code is documented.

Verification:

```bash
./gradlew check
```

Review focus:

- Facts in the documents are derived from the code, not restated from memory.
- Each fact has one owning document; other documents reference it instead of duplicating it.

## 17. Cross-Phase Guardrails

MVP guardrails:

- Blocking Spring MVC.
- PostgreSQL as source of truth.
- Liquibase migrations.
- jOOQ persistence.
- Transactional outbox.
- Fake chain provider in `local`/`test`; HTTP adapter in every other profile.
- No database transaction while calling provider.
- `FOR UPDATE SKIP LOCKED` for outbox poller.
- Domain transition logic independent from Spring.

Not in MVP:

- Redis.
- Kafka or SQS publisher.
- WebFlux.
- Coroutines.
- Balance projection.
- Real blockchain node integration.
- Private key or signing material handling.

## 18. Future Extensions

Future phases can add:

- Real provider adapters.
- Provider-specific block range scans and production indexer adapters.
- Kafka, SQS, or Debezium-based outbox delivery.
- Advisory locks for multi-instance sync coordination.
- Balance projection as a rebuildable read model.
- Multi-tenant authorization.
- Audit event history.

Each extension should start with a spec update before implementation.
