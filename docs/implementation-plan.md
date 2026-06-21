# Implementation Plan

Status: Phase 10 complete  
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

## 11. Cross-Phase Guardrails

MVP guardrails:

- Blocking Spring MVC.
- PostgreSQL as source of truth.
- Liquibase migrations.
- jOOQ persistence.
- Transactional outbox.
- Fake/mock chain provider.
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

## 12. Future Extensions

Future phases can add:

- Real provider adapters.
- Provider cursors and block range scans.
- Kafka, SQS, or Debezium-based outbox delivery.
- Advisory locks for multi-instance sync coordination.
- Balance projection as a rebuildable read model.
- Multi-tenant authorization.
- Audit event history.

Each extension should start with a spec update before implementation.
