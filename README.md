# asset-sync-service

Backend MVP for synchronizing public account, watched-address, and observed transaction lifecycle state. The service uses blocking Spring MVC, Kotlin, PostgreSQL, Liquibase, jOOQ, Testcontainers, and a transactional outbox with a local structured-log publisher.

## Architecture

PostgreSQL is the source of truth for accounts, watched addresses, observed transactions, sync runs, and outbox events. Observed events enter through the REST API or the fake chain provider sync path, pass through an idempotent domain state machine, and create transactional outbox rows when a transaction lifecycle stage changes.

Detailed docs:

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Database](docs/database.md)
- [Failure modes](docs/failure-modes.md)
- [Testing](docs/testing.md)
- [Implementation plan](docs/implementation-plan.md)

Current top-level layout:

```text
.
  Dockerfile
  docker-compose.yml
  build.gradle.kts
  settings.gradle.kts
  docs/
  src/jooqCodegen/
  src/main/kotlin/com/example/assetsync/
  src/main/resources/
  src/test/kotlin/com/example/assetsync/
  src/test/resources/
```

## Prerequisites

- Java 21
- Docker and Docker Compose

Gradle commands that compile the service also run `generateJooq`, which starts a temporary PostgreSQL container through Testcontainers. Generated jOOQ sources are written to `build/generated/sources/jooq/main/kotlin` and are not committed.

## Configuration

Database configuration for the `local` profile:

| Variable | Default | Purpose |
| --- | --- | --- |
| `ASSET_SYNC_DB_HOST` | `localhost` | PostgreSQL host |
| `ASSET_SYNC_DB_PORT` | `5432` | PostgreSQL port |
| `ASSET_SYNC_DB_NAME` | `asset_sync` | Database name |
| `ASSET_SYNC_DB_USER` | `asset_sync` | Database user |
| `ASSET_SYNC_DB_PASSWORD` | `asset_sync` | Database password |
| `ASSET_SYNC_DB_MAX_POOL_SIZE` | `10` | Hikari max pool size |
| `ASSET_SYNC_DB_MIN_IDLE` | `1` | Hikari minimum idle connections |

Runtime configuration:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port used by the Spring Boot app |
| `ASSET_SYNC_OUTBOX_BATCH_SIZE` | `50` | Due outbox rows claimed per poll |
| `ASSET_SYNC_OUTBOX_RETRY_BACKOFF_BASE_DELAY` | `30s` | Linear retry backoff base delay |
| `ASSET_SYNC_OUTBOX_MAX_ERROR_LENGTH` | `1024` | Stored publisher error limit |
| `ASSET_SYNC_OUTBOX_SCHEDULER_ENABLED` | `true` | Enables the scheduled outbox poller |
| `ASSET_SYNC_OUTBOX_SCHEDULER_FIXED_DELAY` | `5s` | Delay between poller runs |
| `ASSET_SYNC_OUTBOX_SCHEDULER_INITIAL_DELAY` | `10s` | Initial delay before first poll |

Actuator endpoints exposed by the app:

- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/info`
- `GET /actuator/metrics`

OpenAPI endpoints:

- `GET /v3/api-docs`
- `GET /swagger-ui.html`

## Gradle

```bash
./gradlew test
./gradlew check
./gradlew bootRun
./gradlew generateJooq
```

For a clean verification run:

```bash
./gradlew clean test
./gradlew clean check
```

## Local Run With Compose PostgreSQL

Start PostgreSQL on an alternate host port:

```bash
ASSET_SYNC_DB_PORT=55432 docker compose up -d postgres
```

Run the app locally against that database:

```bash
ASSET_SYNC_DB_PORT=55432 SERVER_PORT=18080 ./gradlew bootRun
```

Stop containers when done:

```bash
docker compose down -v
```

## Full Docker Compose Run

Build the application jar first. This keeps jOOQ generation and its Testcontainers PostgreSQL dependency on the host, while the Docker image only packages the resulting Spring Boot jar.

```bash
./gradlew clean bootJar
```

Build and start PostgreSQL plus the application:

```bash
ASSET_SYNC_DB_PORT=55433 ASSET_SYNC_HTTP_PORT=18081 docker compose up --build -d
```

Check the app:

```bash
curl -s http://localhost:18081/actuator/health
```

Stop containers when done:

```bash
docker compose down -v
```

## API Examples

Examples below assume the app is running on `http://localhost:18080`. The `local-evm` chain is seeded by Liquibase with `required_confirmations = 3`.

Create an account:

```bash
curl -s -X POST http://localhost:18080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"externalRef":"customer-local-001"}'
```

Register a watched address:

```bash
curl -s -X POST http://localhost:18080/api/v1/accounts/<account-id>/addresses \
  -H 'Content-Type: application/json' \
  -d '{
    "chainId": "local-evm",
    "address": "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
    "asset": "USDC",
    "label": "primary settlement address"
  }'
```

Ingest an observed event:

```bash
curl -s -X POST http://localhost:18080/api/v1/observed-events \
  -H 'Content-Type: application/json' \
  -d '{
    "chainId": "local-evm",
    "txHash": "0x9f1c2d3e4f5061728394a5b6c7d8e9f00112233445566778899aabbccddeeff0",
    "eventIndex": 0,
    "address": "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
    "asset": "USDC",
    "amount": "12.340000000000000000",
    "blockHeight": 9123456,
    "confirmations": 1,
    "direction": "INBOUND",
    "status": "SEEN"
  }'
```

Trigger address sync with the fake provider:

```bash
curl -s -X POST http://localhost:18080/api/v1/addresses/<address-id>/sync
```

Trigger account sync with the fake provider:

```bash
curl -s -X POST http://localhost:18080/api/v1/accounts/<account-id>/sync
```

Get a sync run:

```bash
curl -s http://localhost:18080/api/v1/sync-runs/<sync-run-id>
```

Check health, readiness, and metrics:

```bash
curl -s http://localhost:18080/actuator/health
curl -s http://localhost:18080/actuator/health/readiness
curl -s http://localhost:18080/actuator/metrics
curl -s http://localhost:18080/actuator/metrics/asset.sync.outbox.backlog.total
```

## Outbox And Observability

- The MVP publisher is local only: it emits structured log lines and then marks due outbox rows as `PUBLISHED`.
- Outbox delivery is at-least-once. Consumers must deduplicate by event id or idempotency key.
- The scheduler can be disabled with `ASSET_SYNC_OUTBOX_SCHEDULER_ENABLED=false`.
- Metrics are exposed through Spring Actuator, including observed-event, provider-fetch, sync-run, outbox-event, outbox-batch, and outbox-backlog meters.
- Readiness includes PostgreSQL connectivity.

## MVP Boundaries

This service does not provide custody, signing, private key storage, wallet functionality, or real funds movement. The MVP also does not include a real blockchain node/provider, Kafka, SQS, Redis, balance projection, auth, or multitenancy.

## Final MVP Checklist

Implemented:

- Account registration and lookup.
- Watched-address registration and listing.
- Observed event ingestion.
- Idempotent transaction state transitions.
- Transactional outbox insertion on lifecycle changes.
- Fake provider sync by address and account.
- Scheduled outbox processing with retry/backoff.
- Health, readiness, and metrics through Actuator.

Deferred:

- Transaction listing/read endpoints (`GET /api/v1/transactions*`).
- Real chain provider adapters and provider cursors.
- External brokers such as Kafka or SQS.
- Balance projection read models.
- Auth, account ownership, and multitenancy.
