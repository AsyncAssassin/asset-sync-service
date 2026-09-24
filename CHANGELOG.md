# Changelog

All notable changes to this project are documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the major version is `0`, a minor release may change the public API; such changes are marked **Breaking**.

## [Unreleased]

### Added

- The HTTP bridge credential can travel in a request header: `ASSET_SYNC_PROVIDER_AUTH_HEADER_NAME` (`Authorization` by default) and `ASSET_SYNC_PROVIDER_AUTH_HEADER_VALUE`, which no log line, error, or health detail quotes. Since 0.4.1 refused a user name or password in `base-url`, a token could only live in its path or query. With a credential set, a header name that is not an HTTP token or a value with a line break stops startup; an empty name means `Authorization`.

### Changed

- Syncs skip an address whose chain is disabled, as they skip a disabled address: an account sync leaves it out, `POST /api/v1/addresses/{addressId}/sync` answers `404 Unsupported chain`, and a run of the address queued before fails without calling the provider. Such an address failed every account sync it belonged to, so disabling a chain was not enough to take its addresses out.
- HTTP Basic reads users through a one-minute cache, Spring Security's user cache. A new password works at once, because a mismatch reads the store again; an old password, or a user removed directly in the `users` table or on another instance, keeps working for up to a minute. A change through the service applies at once on the instance that made it.

### Fixed

- `demo` with `asset-sync.provider.type=alchemy` stops at startup with a message that points to `prod`. The first start used to seed an active `local-evm` address for the simulator, and every later start failed the Alchemy preflight on it.
- An address disabled through the API while it syncs, or whose chain is disabled meanwhile, no longer counts as bad provider data. Its next page failed with `Provider returned an event for an address that is not watched`, and an account sync recorded that as an address failure and ended `FAILED`. The address's own run now fails with `Watched address was disabled during the sync.`, and an account pass leaves the address out without an error.
- A provider event whose transaction hash is longer than 128 characters fails its page before anything is written, like the other hash rules. The database refused it only after the events before it on the page were stored, and every later sync of the address failed on it again.
- A control character in `externalRef`, `label`, `chainId`, `asset`, or an ingested `address` fails validation with a `400 validation-failed` that names the field; `externalRef` and `label` may still hold tabs and line breaks. A NUL byte used to reach PostgreSQL, which cannot store it, and came back as `400 database-constraint-violation` with a WARN line.
- A number with a fraction in an integer field fails the request with `400 invalid-request`. Jackson dropped the fraction, so an `eventIndex` of `1.9` was stored as `1`. A bridge page with such a number is now bad data of that address.
- A validation error names the field it is about: `amount: amount must be a non-negative decimal string that fits numeric(38,18)` instead of `amountValid: …`, and the same for `direction` and `status`.
- `HEAD` and `OPTIONS` on the API need the `READ` role, like `GET`; they needed `OPERATOR`. The OpenAPI document declares its `403` from the same set of methods, and says that `401` and `403` do not occur in `local` and `test`.
- One address whose provider fetch keeps failing retryably, such as a timeout on a heavy address or a `5xx`, no longer blocks its account. An account sync failed the whole claim on it and began the next claim at that address again, so the run failed after five attempts and the addresses after it were never synced; each failed claim also lost the progress of the pass. Such an address now waits on a retry list with the attempts and backoff of a run, on its own schedule and without spending the run's `failure_attempts` (`last_requeue_reason = ADDRESS_RETRY`, changeset 018), while the others sync; once its attempts are spent it is recorded as failed, and while it waits the run's `lastError` says so. Pages it commits before a failure count as progress. Three addresses failing in a row still fail the claim as a provider outage, which costs them nothing, and throttling still fails the claim at once. A failed or interrupted claim now saves the pass, so the retried run resumes where it stopped. Before rolling back to 0.4.1, run the SQL in `docs/database.md`.
- An `IllegalArgumentException` from the service's own logic while a sync ingests a page no longer fails the run terminally as `Provider returned an event that breaks a domain invariant`. Only the domain model's checks of event values count as bad provider data; anything else is an unexpected error and is retried as one.
- During a database outage, authenticated `/actuator/metrics` and `/actuator/prometheus` requests keep answering. Every request with credentials read the user store and waited for the connection pool, and every scrape counted the outbox in the database, so Prometheus lost all metrics, including those that show the outage. Users seen in the last ten minutes now stay accepted, and the outbox gauges show their last background refresh.
- A database failure inside an Alchemy fetch no longer counts as an Alchemy outage. It turned provider health `DOWN` and put the raw exception text, which can quote SQL, into the health `error`; the run now records `Database error (<class>).` and health keeps its state.
- A run interrupted by a worker shutdown, or one the full worker pool could not take, is due again at once. It waited for a backoff that grew with every claim of the run, so after a deploy a long account sync waited up to 15 minutes.
- A bridge cursor reaches the bridge byte for byte. It went into the request URL unencoded: a `+`, as in base64, arrived as a space, and a cursor in JSON failed before the request was sent, as a retryable provider outage.
- A bridge page that ends a sync without a cursor, without new events, and at an unchanged safe block succeeds. It failed terminally with `Provider returned a final page without a durable resume cursor or high-water checkpoint`, even when the address had a stored cursor, so two syncs of one address within a finality epoch were enough. The stored cursor or the checkpoint resumes the next sync, as it did before the rule existed.
- A bridge page that holds one event twice with another direction or amount, as a transfer of the address to itself sent as two rows, fails before anything is written, with a message that names the event. When the two rows fall on either side of a page boundary, the second one fails with the same kind of message and the first stays stored. Before, both cases failed as a conflict with stored fields, after the first row had been stored. An exact repeat stays harmless.
- A bridge that answers `401` or `403` fails the run at once as a configuration error and turns provider health `DOWN`, as Alchemy does. It counted as bad data of one address: every address failed while health stayed `UP`, the regression 0.4.0 brought to monitoring. An account sync now ends at the first such answer, from the bridge or from Alchemy, instead of asking once per address. A `404` stays the error of that one address.
- The bridge client no longer follows a redirect, which sent the request, and any token in `base-url`, to the new location. A `3xx` from the bridge or from Alchemy now fails the run at once as a configuration error and turns provider health `DOWN`; Alchemy's was retried as an outage, and a redirect answering the Alchemy startup probe now stops the process like a rejected key.
- A page or JSON-RPC answer whose bytes are not JSON text, which Jackson rejects with a `CharConversionException`, is bad data of that address, from the bridge and from Alchemy. It counted as a transport failure: retried as an outage while provider health went `DOWN`.
- A provider response that trickles in, runs over the byte limit, or carries an error status with an endless body no longer holds a provider thread. The thread kept reading after the provider timeout had given up, because a socket read ignores the interrupt and Spring drained the rest of the body when it closed the response: four such responses took the whole pool, and every other sync spent its retries on `SyncCapacityExceededException`. The read now stops at the cancel, within one read timeout, and the body is closed unread, for the bridge and for Alchemy.
- A sync that finds the provider pool full is requeued as a continuation, like one that finds its cursor lease busy: `last_requeue_reason = PROVIDER_BUSY` (changeset 017), retried after `cursor-lease-retry-delay`, bounded by `max-continuations-per-run`, and an account sync resumes at that address. It spent a `failure_attempts` each time and failed after five, although the service's own capacity says nothing about the provider.

### Security

- An Alchemy transport failure is named by its kind, such as `Alchemy transport failure for network eth-sepolia: timeout (SocketTimeoutException).`, without the request URL, as the bridge's already is. The key was scrubbed from it, but whatever else a custom endpoint template holds reached `last_error`, health, and the logs.

## [0.4.1] - 2026-09-24

### Changed

- **Breaking:** a `base-url` with a user name or password stops startup, with a message that leaves the URL out. The HTTP client never sent them, so a bridge that needs them could only answer `401`.

### Fixed

- A `demo` sync of an `eth-sepolia` address succeeds. The bundled simulator returned `0xsim-…` transaction hashes, which the ingest rules of 0.4.0 refuse on `eth-sepolia`, so every such sync failed with `Provider returned an event with a malformed transaction hash`. Its hashes are now `0x` and the SHA-256 of the chain, address, and asset: deterministic and well formed on every chain.
- The `demo` provider base URL follows `server.port` instead of the `SERVER_PORT` variable, so the self-call into the simulator follows the configured port whichever way it is set, including `--server.port` and IDE run configurations. It still needs a fixed port and no servlet context path.
- The `demo` seeder writes each outbox row as a complete lifecycle event of its transaction: the full payload, an event type that matches the status in its own payload (the reverted transaction carries both its `TRANSACTION_SEEN` and its `TRANSACTION_REVERTED` event), and an idempotency key in the service's format. Published seed events no longer log `null` fields, and a transaction's events are seeded only together with the transaction, so a restart neither re-inserts events that retention removed nor adds events for a transaction it did not write. Seeded transactions and events record the source `demo:seed`. A database seeded by an earlier version keeps its rows; start from an empty one (`docker compose down -v`) to get the current dataset.
- The README demo flow generates its external reference, address, and transaction hash, so it can run again against the same database instead of stopping at `409 Conflict`.
- A request that PostgreSQL cannot serve gets `503 database-unavailable`, as documented. Most endpoints answered `500 internal-error` in `local`, because a transaction that cannot get a connection fails with a `TransactionException`, not a `DataAccessException`. In `demo` and `prod` a request with valid credentials got `401` with a Basic challenge, because the user store behind HTTP Basic could not be read; it now gets the same `503` without a challenge, while a request without credentials keeps its `401`. An SQL error Spring cannot classify, such as a write to a read-only database, gets the `503` too instead of `500`, and a sync run that meets such a failure records `Database error (<class>).` instead of `Unexpected error (<class>).`.
- A query to a PostgreSQL that stops answering without closing the connection, such as a paused container or a network partition, ends after the 40-second JDBC socket timeout instead of holding the request thread and its connection indefinitely. A new connection gives up on the TCP connect after 5 seconds. A `COMMIT` that waits longer, behind a stalled synchronous standby or disk, is cut off too and may still commit; `docs/failure-modes.md` describes what that means for a retry.
- The immutable-conflict `ProblemDetail` examples in `docs/api.md` and `docs/architecture.md` show the fields the service writes, in its order, and `docs/architecture.md` lists the `404` for an unsupported asset and the `409` for a duplicate account; `docs/failure-modes.md` names the metrics and log lines that exist instead of promising them.
- An `amount`, `direction`, or `status` made only of Unicode spaces such as U+00A0 gets `400 validation-failed` naming the field. It passed validation and got `400 invalid-request` without the errors list.
- A bridge page with a `null` element in `events` is a data error for its address instead of a provider outage, so provider health stays `UP`.
- A `Retry-After` beyond the representable range is ignored instead of turning a `429` into a failed request without its backoff.
- The OpenAPI document states the status each operation answers with, where it listed `200` for all of them: `201` for a new account or watched address, `201` or `200` for an ingested event, `202` with `Location` for a sync request. Every operation also refers to the shared `ProblemDetail` errors, `400`, `401`, `500`, and `503`, and each operation that changes state to `403`, all declared once under `application/problem+json`.
- The OpenAPI document covers `/api` only, so under `demo` it no longer lists the chain simulator. Its request schemas no longer name the validation getters `isAmountValid`, `isDirectionValid`, and `isStatusValid` as required fields.
- With `type=alchemy`, registering an address on a chain without an Alchemy network answers `404 Unsupported chain`, whose detail now reads "The chain is not configured, is disabled, or is not served by the active provider." Such an address failed every sync and stopped the next start, and its first sync turned `/actuator/health` into `503`, contrary to the 0.4.0 note that one bad address no longer does. Enabling an address again with `PATCH` runs the same chain and asset checks, and under `start-mode=configured-block` a chain without a start block is not served either.
- With `type=alchemy`, an address the provider cannot serve, because it was registered on a chain without a network before the switch or its asset config was disabled since, and an address whose block holds more events than a page, fail on their own, as for invalid data: the reason is in the run's `last_error` and in `lastDataError` until the next successful fetch, and provider health stays `UP`. The startup preflight still refuses the first two at the next start, so the runbook says to disable such an address or fix its configuration first.
- With `type=alchemy`, an address whose cursor another provider wrote, such as the demo simulator or the HTTP bridge, fails with a message that says so, whatever the cursor's shape, and names the fix in `docs/alchemy-runbook.md`: clearing only `provider_cursor`, which keeps the address's event high-water, so the next sync reads nothing twice.
- The 0.4.0 notes said that after an Alchemy outage at startup sync runs retry until Alchemy answers. They retry with backoff up to `asset-sync.sync.worker.max-attempts`, five by default, and then fail, and the `probe-failed` state clears only with a successful fetch. The README, the runbook, and the preflight's description now say so.

### Security

- `demo`, `local`, and `test` refuse to run together with any other profile. With `prod,demo` on a fresh database, the demo seeder wrote its users with public passwords after the demo-user guard had already looked, so `demo-operator` could change data until the next restart; `staging,demo` and `staging,local` had the same gap without any guard. An environment post-processor now stops such a start before the application context exists, before any placeholder of the other profiles is resolved, and names the profiles.
- The demo-user guard runs in every profile except `demo`, `local`, and `test`: `prod`, `e2e`, a custom profile such as `staging`, and a start without any profile refuse a database that holds the demo users. It is never lazy, it skips a database without the `users` table instead of failing on its own query, and its message names the default profile when none is active and recommends an empty database, because a database the demo ran on also holds the demo dataset. The prod operator account cannot take a `demo` user name.
- `POST /api/v1/observed-events` checks the length of `amount` before it parses the value. The parse takes time quadratic in the length, and validation ran it on a string of any size: a million-digit `amount` held a request thread for about ten seconds before the `400`. A value longer than 80 characters is now refused by its length alone.
- The non-blank check of `externalRef` and `label` runs in linear time. A 100 000-character value that ended in a line break held a request thread for about ten seconds, because validation ran the pattern after the length check had already failed. A value with a line break is no longer reported as blank.
- The application's JSON reader accepts strings of at most 100 000 characters instead of Jackson's 20 million, in request bodies and HTTP bridge pages alike. A longer string in a request field fails the request with `400 invalid-request`, and in a page field the page. Every such field already had a lower limit, so no valid request or page is refused.
- Request and bridge page DTOs skip unknown fields as they read them. Jackson kept them in memory until the known fields were complete, at many times their size: a 20 MB body of small unknown values ran a 256 MiB heap out of memory.
- A request body in YAML gets `415`. Spring MVC read it, because springdoc brings the YAML data format, with none of the JSON limits, although the API speaks JSON only.
- A failure to reach the HTTP bridge no longer quotes the bridge URL. The I/O error text contains the request URL with the path and the query of `base-url`, where a bridge token has to live, and it reached the health details, the WARN log, and the `lastError` of sync runs that the `READ` role sees. The message now names the kind of failure, such as `Provider transport failure: cannot connect (ConnectException).`, and the WARN line adds the cause chain with every URL cut out.
- The JDK's HTTP client and Spring's URI parser log at INFO whatever the root level is: at DEBUG and TRACE they printed the bridge URL with its token.
- `local` and `demo` listen on `127.0.0.1` when started on a host, as the quickstart does with `./gradlew bootRun`. They listened on every interface, so anyone on the same network could use `local` without credentials, and `demo` with its public passwords. `SERVER_ADDRESS=0.0.0.0` opens them again, for a remote `demo` on a trusted network. `docker-compose.yml` sets it for the application container, which is reached through a port published on the host's loopback. The `demo` simulator call goes to `127.0.0.1` instead of `localhost`, which can resolve to `::1` first. An empty `SERVER_ADDRESS` stops the start of `local` and `demo`, because it replaced the loopback default with no address and opened every interface again.

## [0.4.0] - 2026-09-23

### Added

- `429 sync-queue-full` responses carry `Retry-After` with the sync worker claim interval (`asset-sync.sync.worker.fixed-delay`, 5 seconds by default), rounded up to whole seconds.
- The OpenAPI document declares HTTP Basic as the global security requirement, so Swagger UI offers **Authorize** and can switch between the `READ` and `OPERATOR` users.
- `PATCH /api/v1/addresses/{addressId}` for `OPERATOR` sets a watched address `ACTIVE` or `DISABLED`. A disabled address is skipped by account sync, refused by address sync and event ingestion, and resumes from its stored cursor once enabled again.
- HTTP bridge requests carry the address's durable checkpoint as `fromBlockHeight` and `fromEventIndex`, and the bridge page contract is documented in `docs/architecture.md`.
- Observed transactions record the source of their last lifecycle change in the new `source` column (changeset 016), and outbox payloads and their published log lines carry the source of each event: `rest:<user>` for `POST /api/v1/observed-events`, `provider:<http|alchemy|fake>` for a sync.
- CI scans the Docker image with Trivy and fails on HIGH and CRITICAL vulnerabilities that have a fix, in OS packages and in the libraries inside the jar; a finding that does not apply goes to `.trivyignore` with its reason. Trivy runs from its image pinned by digest.
- Dependabot proposes Docker base image updates within Java 21; a newer Java release stays a deliberate migration.
- The README lists the known limitations: `REVERTED` is final, the roles are global, watched addresses are unique across accounts, Basic authentication verifies BCrypt on every request with no limit on failed attempts, the Alchemy rate limiter is per process, `sync_runs` has no retention (`docs/database.md` has the SQL that trims it), the outbox publishes to the log only, and the Alchemy adapter does not record self-transfers.
- `docs/api.md` names the one exception to the `ProblemDetail` format: a request that Tomcat refuses before the application, such as a path with `%2F`, gets Tomcat's minimal HTML page without the error report or the server version.
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
- The documentation no longer lists block-range scans and provider cursors as future work, names the provider that `asset-sync.provider.type` selects where it said the HTTP provider runs in every protected profile, and shows the Alchemy adapter, the sync worker, and the recovery job in the architecture diagrams.
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

[Unreleased]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/AsyncAssassin/asset-sync-service/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/AsyncAssassin/asset-sync-service/releases/tag/v0.1.0
