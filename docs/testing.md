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
- Amount and identity rules: the `numeric(38, 18)` fit without `Int` overflow on extreme exponents, exponent notation, normalization to scale 18, and the per-chain address and transaction-hash formats.
- Application command validation helpers where not covered by API tests.
- Alchemy provider configuration and adapter: static validation rules, rollout rules, JSON-RPC error classification, secret scrubbing in header and path auth modes, health details, the cursor codec, transfer mapping from a captured-shape fixture, amount conversion, the range scan with its one-block fallback, `pageKey` hazards, budget exhaustion, finality and start modes, and the rate limiter.
- Documentation drift guards: every changeset file, meter name, `ProblemDetail` type, and environment placeholder in the code and configuration must appear in the docs.
- Provider health indicators: a data error for one address keeps the indicator `UP` with `lastDataError`, while an availability failure turns it `DOWN`.

Required cases:

| Area | Cases |
| --- | --- |
| New event | `NONE -> SEEN`, `NONE -> CONFIRMED`, `NONE -> REVERTED` |
| Seen lifecycle | `SEEN -> SEEN`, `SEEN -> CONFIRMED`, `SEEN -> REVERTED` |
| Confirmed lifecycle | `CONFIRMED -> CONFIRMED`, `CONFIRMED -> REVERTED` |
| Reverted lifecycle | `REVERTED -> REVERTED`, stale `SEEN` after `REVERTED`, stale `CONFIRMED` after `REVERTED` |
| Confirmations | threshold reached, threshold not reached, threshold `0`, lower stale count ignored |
| Conflicts | amount mismatch, direction mismatch |
| Outbox | idempotency key from the transaction id, status, and version, no event on no-op, no event on conflict |

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
| Sync cursors | lease acquire/release/reclaim, expired lease fencing, stale token rejection, heartbeat extension, high-water preservation |
| Provider pages | missing required fields, explicit empty page, high-water-only final page, byte cap, cursor progress, multi-page success, an invalid amount or transaction hash failing the whole page before any write, a chain disabled after registration failing the run terminally, a final page without a cursor keeping the stored cursor only when it had no events, and the next fetch carrying the checkpoint as `fromBlockHeight` and `fromEventIndex` |
| Sync continuation | page failure retry from checkpoint, continuation count separate from failure attempts |
| Account traversal | busy early address is skipped while later addresses are processed; a pass larger than one claim resumes from its keyset and completes; an address registered between claims joins the pass; an address with pages left is drained before the scan moves on; a busy address is revisited after the scan; a terminally failing address is reported in `last_error` while the others sync, and disabling it lets the account sync pass |
| Sync shutdown | draining finishes an in-flight run, interruption requeues without failure budget, a stopped worker refuses claims |
| Asset registry | changeset 015 seeds and constraints, unknown and disabled asset rejection, disabled chain precedence, Sepolia casing normalization, rollout preflight query |
| Protected security chain | a path the firewall rejects keeps its `400` without a Basic challenge, with or without credentials; a path that Tomcat refuses before Spring (`%2F`) gets Tomcat's bare `400` page without the error report or the server version; `/simulator` requires authentication in `prod` and stays open under `demo`, where a non-positive `limit` is a `400` ProblemDetail; the OpenAPI document declares HTTP Basic as the global requirement; an HTTP bridge that returns no cursor resumes from the checkpoint the provider sends |
| Credentials and sources | `prod` refuses to start on a database that holds the demo users and names the SQL that removes them; events record `rest:<user>` or `provider:<type>` as their source on the row and in the outbox payload; a database failure reaches `last_error` as its class only; authenticated health shows no `diskSpace` component |
| Provider selection | `prod` boots with the HTTP bridge by default and rejects a blank `base-url`; `type=alchemy` boots without `base-url`, wires only Alchemy beans, probes `eth-sepolia` with a bearer token, exposes health without the key, and fails fast on a missing key, HTTP 401, an enabled chain with active watched addresses but no network mapping, and legacy watched addresses; a fresh database boots with the unmapped seeded `local-evm` chain enabled, and HTTP 503 at startup boots the service with health `DOWN` in the `probe-failed` state until the first successful fetch; `local` keeps the fake provider |
| Alchemy sync | the real worker against the Alchemy adapter and a scripted JSON-RPC stub in path auth mode: `registration-safe` idle start without backfill, ingestion and confirmation of whole pages below the finality frontier with outbox events, cursor and high-water advancement, retry from the durable cursor after HTTP 500 and after a transport failure with a scrubbed `last_error`, `Retry-After` on 429, and continuation across claims without failure attempts |
| Profile guards | `demo`, `local`, and `test` combined with `prod` or a custom profile stop before anything reaches the database, also without the prod secrets, and name the profiles; the demo-user guard refuses a database with the demo users under `prod`, a custom `staging`, and no profile, skips a database without the `users` table, and runs in exactly the profile sets the combination guard lets start except `demo`; the prod admin cannot take a demo user name |

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
| `POST /api/v1/accounts/{accountId}/addresses` | create success, account not found, chain disabled/not found, duplicate address, validation failures, per-chain address format |
| `GET /api/v1/accounts/{accountId}/addresses` | list success, account not found |
| `PATCH /api/v1/addresses/{addressId}` | disable and enable again, unchanged status keeps `updated_at`, disabled address refused by sync, unknown address, invalid status, operator-only in protected profiles |
| `POST /api/v1/observed-events` | created, updated, no-change duplicate, immutable conflict, validation failures, per-chain transaction-hash format, extreme exponent amounts, identities that differ only around `:` keeping separate outbox events |
| `POST /api/v1/addresses/{addressId}/sync` | success, address not found, provider timeout, multi-page checkpointing, retry from page cursor, full queue with `Retry-After` |
| `POST /api/v1/accounts/{accountId}/sync` | success, account not found, provider failure, busy cursor fairness |
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
- Provider pagination tests should assert checkpoint advancement happens only after whole-page ingestion.
- Retry budget tests should assert `failure_attempts` and `continuation_count` move independently.
- Use latches or barriers for deterministic concurrency where needed.

## 6. Gradle Commands

The current Gradle project runs both unit and integration tests under `test`; there is no separate `integrationTest` task.

```bash
./gradlew test
./gradlew check
./gradlew bootRun
```

The Alchemy live smoke is env-gated and skipped unless `ALCHEMY_LIVE_SMOKE=true`; it needs a real key, Docker, and an address with Sepolia USDC history (procedure in `docs/alchemy-runbook.md`):

```bash
ALCHEMY_LIVE_SMOKE=true ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY='<key>' ALCHEMY_LIVE_SMOKE_ADDRESS='0x...' \
./gradlew test --tests 'com.example.assetsync.e2e.AlchemyLiveSmokeTests'
```

The `ALCHEMY_LIVE_SMOKE*` variables are declared as test inputs, so a changed gate or target re-runs the task; add `--rerun` to repeat an unchanged successful smoke.

CI scans the Docker image with Trivy and fails on HIGH and CRITICAL vulnerabilities that have a fix, in OS packages and in the libraries inside the jar. The same scan runs locally after `./gradlew bootJar`:

```bash
docker build -t asset-sync-service:ci .
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/workspace:ro" -w /workspace \
  aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 \
  image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --no-progress \
  --timeout 15m asset-sync-service:ci
```

The first run downloads the vulnerability and Java databases, about 1.4 GB each unpacked; the timeout covers that download. A finding that does not apply goes to `.trivyignore` with its id and the reason, so the exception stays reviewable.

If jOOQ generation is configured as a separate task:

```bash
./gradlew generateJooq
```

Do not add a markdown or test tool only for the Specs phase.

## 7. Future Extensions

Deferred test areas:

- Scan contract tests for providers beyond the Alchemy ERC-20 adapter and the generic HTTP page contract.
- Broker publisher tests for Kafka, SQS, or CDC.
- Balance projection rebuild tests.
- Multi-tenant authorization tests.
- Scheduler/advisory-lock tests for multi-instance coordination.
