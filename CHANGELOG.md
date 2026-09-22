# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the major version is `0`, a minor release may change the public API; such changes are marked **Breaking**.

## [Unreleased]

### Added

- `asset_configs` registry (changeset 015) keyed by chain and asset, seeded with `USDC` on `local-evm`, `eth-sepolia`, and `eth-mainnet` (disabled), plus the `eth-sepolia` and `eth-mainnet` chain configs; EVM identity normalization now covers those chains.
- Dependabot configuration for weekly Gradle and GitHub Actions updates, grouped by Spring, Kotlin, and Jackson; major bumps of Spring Boot and springdoc are ignored so patch releases keep arriving.
- Chain provider selection through `asset-sync.provider.type` (`http` by default, `alchemy`): the HTTP bridge beans exist only for `http`; `alchemy` binds `asset-sync.provider.alchemy.*`, validates the settings and the asset registry at startup, probes every required Alchemy network with `eth_blockNumber` using header (default) or path authentication, keeps the API key out of logs, errors, health details, and `sync_runs.last_error`, and maps `eth-sepolia` as the first chain.
- Alchemy ERC-20 transfer adapter: finality-lagged range scan with a one-block fallback for paged windows, whole-block emission sorted by block and log index, a block-boundary cursor `{"v":1,"p":"alchemy","nextBlock":N}`, decimal-adjusted amounts from `rawContract.value` and registry decimals, self-transfer and wrong-token skips recorded in the checkpoint, `registration-safe` and `configured-block` start modes, a per-fetch RPC and time budget, and a local token bucket in front of every JSON-RPC call.
- `ProviderConfigurationException` as a terminal sync failure class for rejected credentials, unmapped chains, missing registry rows, and blocks that exceed the page size; it never consumes the retry budget.

### Changed

- **Breaking:** watched-address registration rejects assets that are not registered and enabled for the chain with `404` and the title `Unsupported asset`; local, test, and demo flows keep working through the seeded `local-evm` `USDC` row.
- Graceful shutdown drains in-flight sync runs for up to `asset-sync.sync.worker.shutdown-timeout` and requeues runs interrupted afterwards without consuming their retry budget.
- `401` and `403` produced by the security filter chain are `ProblemDetail` responses with `requestId`; `401` keeps the `WWW-Authenticate: Basic` challenge.
- `ASSET_SYNC_PROVIDER_BASE_URL` in the `prod` profile is required only for the `http` provider type; a blank value still fails the boot there, while `alchemy` ignores it. Provider connect and read timeouts now live in the base configuration for every profile.

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

[Unreleased]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/AsyncAssassin/asset-sync-service/releases/tag/v0.1.0
