# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the major version is `0`, a minor release may change the public API; such changes are marked **Breaking**.

## [Unreleased]

### Added

- `429 sync-queue-full` responses carry `Retry-After` with the sync worker claim interval (`asset-sync.sync.worker.fixed-delay`, 5 seconds by default), rounded up to whole seconds.
- The OpenAPI document declares HTTP Basic as the global security requirement, so Swagger UI offers **Authorize** and can switch between the `READ` and `OPERATOR` users.
- `PATCH /api/v1/addresses/{addressId}` for `OPERATOR` sets a watched address `ACTIVE` or `DISABLED`. A disabled address is skipped by account sync, refused by address sync and event ingestion, and resumes from its stored cursor once enabled again.
- HTTP bridge requests carry the address's durable checkpoint as `fromBlockHeight` and `fromEventIndex`, and the bridge page contract is documented in `docs/architecture.md`.
- Observed transactions record the source of their last lifecycle change in the new `source` column (changeset 016), and outbox payloads and their published log lines carry the source of each event: `rest:<user>` for `POST /api/v1/observed-events`, `provider:<http|alchemy|fake>` for a sync.
- CI scans the Docker image with Trivy and fails on HIGH and CRITICAL vulnerabilities that have a fix, in OS packages and in the libraries inside the jar; a finding that does not apply goes to `.trivyignore` with its reason. Trivy runs from its image pinned by digest.
- Dependabot proposes Docker base image updates.
- `README.md` and `docs/alchemy-runbook.md` describe two limits of reading every Alchemy block once: `registration-safe` fixes the start block at an address's first sync, not at its registration, so a new address should be synced right away; and a `required_confirmations` above the depth of the finality frontier leaves events `SEEN`.

### Changed

- **Breaking:** watched-address registration checks the address format of its chain: `0x` and 40 hex digits on `eth-sepolia` and `eth-mainnet`, no whitespace, `/`, or `:` on `local-evm`, and no control characters on any chain. A malformed address returns `400 invalid-request`. Observed events apply the same rules to `txHash`, with 64 hex digits on `eth-sepolia` and `eth-mainnet`.
- **Breaking:** outbox idempotency keys are built from the observed transaction id as `observed-tx:{transactionId}:status:{status}:v:{version}` instead of the natural key. Existing rows keep their keys, and no new key can equal an old one.
- In an account sync, an address that fails terminally ends only itself: the pass continues with the other addresses, and the run finishes `FAILED` with `<n> of <m> addresses failed terminally: <addressId>: <error>; ...` in `lastError`.
- Provider health turns `DOWN` only on availability failures. Invalid data for one address, such as a `4xx` answer, malformed JSON, or an unmappable Alchemy row, keeps the state and appears as `lastDataError`, so one bad address no longer turns `/actuator/health` into `503`.
- The recovery job runs every minute and first a minute after startup instead of every five minutes, so a run left `RUNNING` by a crashed worker is requeued about a minute after its lease expires.
- A sync run whose event fails ingestion deterministically fails at once instead of spending its retries: an event that breaks a domain invariant or an ingest rule is provider data invalid, and events of a chain disabled after registration are a provider configuration failure.
- With `asset-sync.provider.type=alchemy`, an Alchemy that is unavailable at startup (`5xx`, `429`, a timeout, a transport error) no longer stops the service. It starts with the `alchemyChainProvider` health component `DOWN` in the new `probe-failed` state, sync runs retry with backoff, and the first successful fetch clears the state; the REST API and outbox publishing keep working. A rejected key (`401`, `403`, JSON-RPC `-32600`) still stops startup.
- The Alchemy startup preflight requires a network mapping only for enabled chains with active watched addresses and logs other unmapped chains as `alchemy_preflight_unmapped_chains_skipped`, so a fresh database boots without disabling the seeded `local-evm` chain first.

### Fixed

- In the protected profiles, a request that Spring Security's firewall rejects before authentication, for example one with `//` or `;` in its path, gets `400` instead of a `401` Basic challenge, also with valid credentials: error dispatches no longer require authentication.
- The `demo` simulator answers a non-positive `limit` with a `400` ProblemDetail instead of `500`.
- An amount with an extreme exponent such as `1e2147483647` passed the `numeric(38, 18)` check through an `Int` overflow and was stored as a confirmed zero. Digits are now counted in `Long`, exponent notation within range still works, and amounts are stored at scale 18. REST ingestion and provider pages share this rule.
- A provider page is checked in full before its first event is written: an amount that is negative or does not fit `numeric(38, 18)`, which PostgreSQL used to round silently, or a malformed transaction hash fails the run terminally and writes nothing.
- Two transactions whose hash or address contains `:` could share an outbox idempotency key, and the lifecycle event of the second one was dropped.
- An account sync that did not fit one claim never completed: each claim had to visit every address again, so an account with more addresses than the per-claim budget allows ran until the continuation limit failed it. The pass now keeps its keyset in the run checkpoint and completes over several claims.
- A final HTTP bridge page without a cursor cleared the stored cursor, so the next sync fetched the bridge's history from the start and failed on the first event behind the checkpoint. The next request now carries the checkpoint, and the stored cursor is kept when the final page had no events.
- Docker Compose gives the application a 40-second stop grace period, longer than the 30-second graceful-shutdown phase. With Docker's default 10 seconds a sync run still in flight was killed before it could requeue, waited in `RUNNING` for recovery, and lost a retry attempt.

### Security

- Docker Compose publishes the API and PostgreSQL on `127.0.0.1` only. `ASSET_SYNC_HTTP_BIND_ADDRESS` opens the API port to other machines, for use with a protected profile; PostgreSQL stays on loopback.
- The protected security chain opens `/simulator/**` only under `demo`. In `prod` and `e2e`, which serve no simulator, the path requires authentication.
- Addresses and transaction hashes with control characters are rejected on every chain, so they can no longer forge log lines.
- The `prod` profile refuses to start on a database whose user store holds the `demo` users, whose passwords are public, and its message names the SQL that removes them; it never changes the data itself.
- A sync run's `lastError` no longer quotes SQL: a database failure is stored as its class, for example `Database error (DataIntegrityViolationException).`, other unexpected failures as `Unexpected error (<class>).`, and the full exception goes to the log.
- The disk-space health indicator is off, because its details showed the working directory's absolute path to every `READ` user.
- The documentation explains why CSRF protection is off and what a browser that caches Basic credentials exposes.
- Tomcat 10.1.60, pgjdbc 42.7.13, Log4j API 2.25.5, and commons-lang3 3.20.0 are pinned over the Spring Boot BOM, which gets no more open-source releases. None of the advisories they close is reachable in the service, but scanners reported them, three as CRITICAL.
- The Docker image is built on `eclipse-temurin:21.0.12_8-jre-noble` instead of `21.0.8_9-jre`: the JRE is four quarterly updates newer, and the 11 HIGH advisories in the base image's GnuPG and OpenSSL packages are gone.
- CI runs with a read-only `GITHUB_TOKEN` and actions pinned to commit SHAs, and the Gradle wrapper verifies the checksum of the distribution it downloads.

## [0.3.0] - 2026-09-23

### Added

- `asset_configs` registry (changeset 015) keyed by chain and asset, seeded with `USDC` on `local-evm`, `eth-sepolia`, and `eth-mainnet` (disabled), plus the `eth-sepolia` and `eth-mainnet` chain configs; EVM identity normalization now covers those chains.
- Dependabot configuration for weekly Gradle and GitHub Actions updates, grouped by Spring, Kotlin, and Jackson; major bumps of Spring Boot and springdoc are ignored so patch releases keep arriving.
- Chain provider selection through `asset-sync.provider.type` (`http` by default, `alchemy`): the HTTP bridge beans exist only for `http`; `alchemy` binds `asset-sync.provider.alchemy.*`, validates the settings and the asset registry at startup, probes every required Alchemy network with `eth_blockNumber` using header (default) or path authentication, keeps the API key out of logs, errors, health details, and `sync_runs.last_error`, and maps `eth-sepolia` as the first chain.
- Alchemy ERC-20 transfer adapter: finality-lagged range scan with a one-block fallback for paged windows, whole-block emission sorted by block and log index, a block-boundary cursor `{"v":1,"p":"alchemy","nextBlock":N}`, decimal-adjusted amounts from `rawContract.value` and registry decimals, self-transfer and wrong-token skips recorded in the checkpoint, `registration-safe` and `configured-block` start modes, a per-fetch RPC and time budget, and a local token bucket in front of every JSON-RPC call.
- Integration tests that drive the real sync worker against the Alchemy adapter and a scripted JSON-RPC stub: idle start, ingestion below the finality frontier, retry from the durable cursor with a scrubbed `last_error`, `Retry-After` on 429, and continuation across claims.
- `docs/alchemy-runbook.md` (key, rollout, inspection, failure actions) and the env-gated `AlchemyLiveSmokeTests` against Alchemy Sepolia, skipped in CI.
- Micrometer meters for the Alchemy adapter: JSON-RPC calls and latency per network, method, and result, one-block fallbacks, narrowings, and skipped rows.
- A documentation drift guard for environment variables: every `${...}` placeholder in the main configuration must be listed in the README, which now documents the worker, recovery, retention, pagination, and `prod` datasource variables it was missing.
- `ProviderConfigurationException` as a terminal sync failure class for rejected credentials, unmapped chains, missing registry rows, and blocks that exceed the page size; it never consumes the retry budget.

### Security

- springdoc-openapi 2.9.1 ships a swagger-ui with the DOMPurify fix for CVE-2026-75838 (GHSA-748c-f84h-hp2v).

### Changed

- **Breaking:** watched-address registration rejects assets that are not registered and enabled for the chain with `404` and the title `Unsupported asset`; local, test, and demo flows keep working through the seeded `local-evm` `USDC` row.
- Graceful shutdown drains in-flight sync runs for up to `asset-sync.sync.worker.shutdown-timeout` and requeues runs interrupted afterwards without consuming their retry budget.
- `401` and `403` produced by the security filter chain are `ProblemDetail` responses with `requestId`; `401` keeps the `WWW-Authenticate: Basic` challenge.
- `ASSET_SYNC_PROVIDER_BASE_URL` in the `prod` profile is required only for the `http` provider type; a blank value still fails the boot there, while `alchemy` ignores it. Provider connect and read timeouts now live in the base configuration for every profile.
- The Alchemy adapter narrows a paged window to the blocks before its page boundary and re-queries them before draining the boundary block alone, so dense stretches no longer cost three calls per block; `max-rpc-calls-per-fetch` defaults to `8` so the narrowing fits the default budget.
- Toolchain: Kotlin 2.4.20 with `kotlin-stdlib`/`kotlin-reflect` aligned through the Boot BOM's `kotlin.version`, Spring Boot 3.5.16, Gradle 9.7.1, and GitHub Actions `checkout` v7, `setup-java` v6, and `gradle/actions` v6; the Jackson BOM override stays because Boot 3.5.16 still manages 2.21.4.

## [0.2.0] - 2026-09-22

### Changed

- **Breaking:** `POST /api/v1/addresses/{addressId}/sync` and `POST /api/v1/accounts/{accountId}/sync` enqueue a durable run and return `202 Accepted` with a `Location` header instead of executing the sync inline and returning `200 OK`. Clients poll `GET /api/v1/sync-runs/{id}`.
- `ChainProviderPort` is page-based: providers return bounded pages with a cursor, `hasMore`, and block high-water fields instead of one unbounded event list.
- API hardening: chain-specific identity normalization for `local-evm`, request length limits, `X-Request-Id` propagation, and `ProblemDetail` responses for every API-layer error, including unknown routes, unsupported methods and media types, missing parameters, and unexpected failures.

### Added

- Durable asynchronous sync: `sync_runs` is the queue, with `QUEUED` and `RUNNING` states, worker claims through `FOR UPDATE SKIP LOCKED`, leases and heartbeats, fenced completion, jittered retry backoff, and recovery of expired leases and legacy stale runs.
- Per-watched-address `sync_cursors` with checkpoint leases, heartbeat extension, fenced advancement after whole-page ingestion, continuation budgets separate from the retry budget, and account traversal fairness.
- HTTP chain provider adapter with bounded response bodies, `429` `Retry-After` handling, and a provider health indicator; profile-specific wiring keeps the fake provider in `local` and `test`.
- Outbox hardening: lease-based claiming, fenced completion, terminal `DEAD` status, published-row retention, scheduler failure accounting, and backlog and dead-letter gauges.
- HTTP Basic security with role-based access in every non-local profile, a database-backed user store, an environment-provisioned production admin, and a `demo` profile with a seeded lifecycle dataset and an in-process provider simulator; Docker Compose honors `SPRING_PROFILES_ACTIVE`.
- Database changesets 006 to 014: input length constraints and their validation, outbox `DEAD` status and partial indexes, `local-evm` identity normalization with fail-fast preconditions, Spring Security tables, the `block_height >= 0` invariant, the async sync queue, and provider pagination cursors, each with operator notes in `docs/database.md`.
- Health component details for authenticated callers, build name and version at `/actuator/info`, documentation drift guards in the test suite, and real-boot end-to-end tests covering the `prod`, `e2e`, and `demo` wiring.

### Fixed

- Jackson pinned to 2.21.7 to pick up the fix for GHSA-5jmj-h7xm-6q6v while Spring Boot still manages a vulnerable version.
- Startup warnings from the auto-configured in-memory security user and from springdoc removed.

### Removed

- Dead synchronous-era exception types and the API handlers that became unreachable once sync turned asynchronous.

## [0.1.1] - 2026-06-21

### Changed

- Portfolio and release-readiness polish: README rewrite, screenshots, GitHub Actions CI workflow, and roadmap aligned with the CI versus deployment status. No runtime behavior changes.

## [0.1.0] - 2026-06-21

### Added

- Initial MVP: accounts, watched addresses, observed event ingestion through an idempotent lifecycle state machine, transactional outbox with a structured-log publisher, synchronous sync through a fake chain provider, Liquibase schema (changesets 001 to 005), jOOQ persistence, Testcontainers-backed tests, and Actuator health, metrics, and OpenAPI.

[Unreleased]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/AsyncAssassin/asset-sync-service/releases/tag/v0.1.0
