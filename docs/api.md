# API Specification

Status: Current MVP API  
Scope: MVP REST API  
Source of truth: `docs/architecture.md`

## 1. Versioning And Conventions

All MVP endpoints are exposed under `/api/v1`. The version is part of the URL because the API is intended to be consumed by external systems and must support future incompatible changes without content negotiation ambiguity.

Conventions:

- Request and response bodies use JSON; a body of any other content type, YAML included, gets `415`. A string longer than 100 000 characters in a request field fails the request with `400 invalid-request` while the body is read, before any field rule runs. Unknown fields are ignored, whatever their size.
- Timestamps use UTC ISO-8601 strings.
- Identifiers use UUID strings.
- Monetary amounts are encoded as decimal strings and stored with `numeric(38, 18)` precision.
- Enum values use upper snake case, for example `SEEN`, `CONFIRMED`, and `INBOUND`.
- API DTOs are mapped to application commands before use-case execution.
- Controllers do not depend on jOOQ or database-generated classes.
- Errors use Spring `ProblemDetail` with `application/problem+json`. The exception is a request that Tomcat refuses before the application sees it, such as a path with an encoded slash (`%2F`): it gets Tomcat's minimal HTML page with the status line only, without the error report or the server version, because `server.error.include-stacktrace` keeps its default `never`.

Authentication:

- Every profile except `local` and `test` requires HTTP Basic against the database user store: `GET` endpoints need the `READ` or `OPERATOR` role, every mutation needs `OPERATOR`. Health probes stay open.
- The API keeps no session, so CSRF protection is off. A browser that has cached Basic credentials for the service would still attach them to a cross-site form `POST`; the only endpoints such a form can reach are the two body-less sync endpoints, and the effect is an extra sync run. Do not log into the API from a browser used for other sites, and put the service behind a gateway when it is exposed.
- Every authenticated request verifies the BCrypt hash of the password (strength 10, about 70 ms of CPU), and nothing limits failed attempts. The gateway in front of an exposed service should rate-limit requests; token authentication (OAuth2 or JWT) is the next step beyond the MVP.

Compatibility rules:

- Additive response fields are allowed in `/api/v1`.
- The current service is still a pre-production MVP. The async sync change intentionally keeps
  `/api/v1` while changing sync POST semantics from final `200 OK` to enqueue-only `202 Accepted`.
- Future production breaking changes should use a new URL version.

## 2. MVP Endpoints

### Accounts

```text
POST /api/v1/accounts
GET  /api/v1/accounts/{accountId}
```

### Watched Addresses

```text
POST  /api/v1/accounts/{accountId}/addresses
GET   /api/v1/accounts/{accountId}/addresses
PATCH /api/v1/addresses/{addressId}
```

### Observed Events

```text
POST /api/v1/observed-events
```

### Sync Runs

```text
POST /api/v1/addresses/{addressId}/sync
POST /api/v1/accounts/{accountId}/sync
GET  /api/v1/sync-runs/{syncRunId}
```

Deferred read endpoints, not exposed by the current implementation:

```text
GET  /api/v1/transactions
GET  /api/v1/transactions/{transactionId}
```

## 3. Create Account

Creates an account used to group watched addresses. An account does not imply custody, signing capability, or ownership of private material.

Request:

```http
POST /api/v1/accounts
Content-Type: application/json
```

```json
{
  "externalRef": "customer-123"
}
```

Response:

```http
HTTP/1.1 201 Created
Location: /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1
Content-Type: application/json
```

```json
{
  "id": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
  "externalRef": "customer-123",
  "status": "ACTIVE",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:00Z"
}
```

Validation:

- `externalRef` is optional.
- If provided, `externalRef` must be non-blank after trimming.
- Duplicate `externalRef` values are rejected with `409 Conflict`.
- `externalRef = null` creates a new anonymous account on every request; deployments should quota or authenticate callers before exposing that mode.

## 4. Get Account

Request:

```http
GET /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1
```

Response:

```json
{
  "id": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
  "externalRef": "customer-123",
  "status": "ACTIVE",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:00Z"
}
```

## 5. Register Watched Address

Registers a public address and asset for observation. The natural identity is `chainId + address + asset`, so the same address and asset cannot be registered twice on the same chain even under different accounts in the MVP.

Request:

```http
POST /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1/addresses
Content-Type: application/json
```

```json
{
  "chainId": "local-evm",
  "address": "0xabc123",
  "asset": "USDC",
  "label": "primary settlement address"
}
```

Response:

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

No `Location` header is returned because the MVP does not expose a canonical watched-address item read endpoint. The created address is returned in the response body and can be seen through `GET /api/v1/accounts/{accountId}/addresses`.

```json
{
  "id": "6df29db1-96d2-4665-8945-266c7f90138e",
  "accountId": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
  "chainId": "local-evm",
  "address": "0xabc123",
  "asset": "USDC",
  "label": "primary settlement address",
  "status": "ACTIVE",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:00Z"
}
```

Validation:

- `accountId` must reference an existing account.
- `chainId` must reference an enabled chain configuration.
- `asset` must be registered and enabled for that chain in the asset registry; unknown or disabled assets return `404` with the title `Unsupported asset`. The seeded registry covers `USDC` on `local-evm` and `eth-sepolia`; `eth-mainnet` is seeded disabled.
- `address` is required and must be non-blank.
- `address` must be well formed for the chain once normalized: `0x` followed by 40 hex digits, in any casing, on `eth-sepolia` and `eth-mainnet`; no whitespace, `/`, or `:` on `local-evm`, which keeps accepting synthetic identifiers such as `0xdemoaddr`; no control characters on any chain. A malformed address returns `400` with `invalid-request` and the `chainId`, after the chain and asset checks.
- `asset` is required and must be non-blank.
- `label` is optional; if provided, it must be non-blank after trimming.
- Duplicate canonical `chainId + address + asset` registrations are rejected with `409 Conflict`.

Address normalization is chain-specific. For the EVM chains `local-evm`, `eth-sepolia`, and `eth-mainnet`, address and transaction-hash identity is lower-case and asset identity is upper-case before uniqueness checks and format rules. Other chains currently trim and preserve exact strings until their policies are defined.

## 6. List Watched Addresses

Request:

```http
GET /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1/addresses?page=0&size=50
```

Response:

```json
{
  "items": [
    {
      "id": "6df29db1-96d2-4665-8945-266c7f90138e",
      "accountId": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
      "chainId": "local-evm",
      "address": "0xabc123",
      "asset": "USDC",
      "label": "primary settlement address",
      "status": "ACTIVE",
      "createdAt": "2026-06-19T00:00:00Z",
      "updatedAt": "2026-06-19T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 50,
  "hasNext": false
}
```

`page` must be between `0` and `10000`. `size` must be between `1` and `100`. Values outside those bounds return `400 Bad Request` with `invalid-pagination`.

## 7. Update Watched Address Status

Enables or disables a watched address. Requires the `OPERATOR` role in the protected profiles.

Request:

```http
PATCH /api/v1/addresses/6df29db1-96d2-4665-8945-266c7f90138e
Content-Type: application/json
```

```json
{
  "status": "DISABLED"
}
```

Response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

The body is the watched address in its new status, in the same shape as the registration response.

Behavior:

- `status` is required and must be `ACTIVE` or `DISABLED`; any other value returns `400` with `validation-failed`.
- An unknown address id returns `404` with `not-found`.
- Setting the current status again returns the address unchanged.
- A disabled address is skipped by account sync, refused by `POST /api/v1/addresses/{addressId}/sync` with `404`, and does not accept observed events. Enabling it again resumes sync from its stored cursor.
- This is the way to take an address that keeps failing terminally out of account syncs; see Start Account Sync.

## 8. Ingest Observed Event

Ingests one observed transaction event. This endpoint is idempotent by natural key and state-machine semantics.

Request:

```http
POST /api/v1/observed-events
Content-Type: application/json
```

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

Created response:

```http
HTTP/1.1 201 Created
```

No `Location` header is returned because transaction read endpoints are deferred in the MVP. The created transaction id is returned in the response body.

```json
{
  "transactionId": "5e1c9c94-6e36-4fb9-bb27-67800e88ac51",
  "result": "CREATED",
  "status": "SEEN",
  "outboxEvents": ["TRANSACTION_SEEN"]
}
```

Updated response:

```http
HTTP/1.1 200 OK
```

```json
{
  "transactionId": "5e1c9c94-6e36-4fb9-bb27-67800e88ac51",
  "result": "UPDATED",
  "status": "CONFIRMED",
  "outboxEvents": ["TRANSACTION_CONFIRMED"]
}
```

Duplicate no-op response:

```http
HTTP/1.1 200 OK
```

```json
{
  "transactionId": "5e1c9c94-6e36-4fb9-bb27-67800e88ac51",
  "result": "NO_CHANGE",
  "status": "SEEN",
  "outboxEvents": []
}
```

Validation:

- `chainId`, `txHash`, `address`, and `asset` are required and must be non-blank.
- `txHash` follows the address format rules of its chain, with 64 hex digits instead of 40 on `eth-sepolia` and `eth-mainnet`; a malformed hash returns `400` with `invalid-request`.
- `eventIndex` is required and must be `>= 0`.
- `amount` is required, must parse as a non-negative decimal, exponent notation such as `1e2` included, and must fit `numeric(38, 18)`: at most 20 integer and 18 fraction digits. It is stored at scale 18. A value longer than 80 characters is refused by its length alone, before it is parsed.
- `blockHeight` is required and must be `>= 0`.
- `confirmations` is required and must be `>= 0`.
- `direction` must be `INBOUND` or `OUTBOUND`.
- `status` must be `SEEN`, `CONFIRMED`, or `REVERTED`.
- The watched address identified by `chainId + address + asset` must exist and be active.

Source:

- The authenticated caller becomes the source of the change, `rest:<user>` (`rest:anonymous` in `local` and `test`), stored on the transaction row and in the outbox event it creates. Events ingested by a sync carry `provider:<type>` instead, so a status reported through the API stays distinguishable from provider data.

Idempotency behavior:

- The natural idempotency key is `chainId + txHash + eventIndex + address + asset`.
- If no row exists, the service creates an observed transaction and creates one lifecycle outbox event for the resulting status.
- If a row exists, the service locks it with `FOR UPDATE`, evaluates the domain transition, and writes only meaningful changes.
- Exact duplicates return `NO_CHANGE` and create no outbox event.
- Stale events return `NO_CHANGE` and create no outbox event.
- Immutable field conflicts return `409 Conflict`.
- `REVERTED` is terminal in the MVP.

## 9. Deferred Transaction List Endpoint

The current implementation does not expose transaction listing/read endpoints. The response shape below is retained as a deferred design target.

The future endpoint should return observed transactions for inspection. Pagination is recommended when this endpoint is implemented.

Request:

```http
GET /api/v1/transactions?accountId=4f6f3d3a-40b5-46fd-86cc-7105d19f17d1&status=CONFIRMED
```

Planned filters:

- `accountId`
- `addressId`
- `chainId`
- `txHash`
- `status`

Response:

```json
{
  "items": [
    {
      "id": "5e1c9c94-6e36-4fb9-bb27-67800e88ac51",
      "watchedAddressId": "6df29db1-96d2-4665-8945-266c7f90138e",
      "chainId": "local-evm",
      "txHash": "0xdeadbeef",
      "eventIndex": 0,
      "address": "0xabc123",
      "asset": "USDC",
      "amount": "12.340000000000000000",
      "blockHeight": 9123456,
      "confirmations": 3,
      "direction": "INBOUND",
      "status": "CONFIRMED",
      "firstSeenAt": "2026-06-19T00:00:00Z",
      "lastSeenAt": "2026-06-19T00:05:00Z",
      "confirmedAt": "2026-06-19T00:05:00Z",
      "revertedAt": null
    }
  ]
}
```

## 10. Deferred Get Transaction Endpoint

The current implementation does not expose this endpoint.

Request:

```http
GET /api/v1/transactions/5e1c9c94-6e36-4fb9-bb27-67800e88ac51
```

Response shape is the same transaction object used by the list endpoint.

## 11. Start Address Sync

Enqueues a durable sync run for one watched address. The POST request validates that the address exists, creates or reuses an in-flight `sync_runs` row, and returns before provider work starts. Local/test profiles use the fake provider; other profiles use the provider that `asset-sync.provider.type` selects, the HTTP bridge or Alchemy, from the background worker. Provider pagination and cursor checkpoints are internal; the public API exposes the durable run state only.

Request:

```http
POST /api/v1/addresses/6df29db1-96d2-4665-8945-266c7f90138e/sync
```

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

Behavior:

- Duplicate in-flight requests for the same target return the existing `QUEUED` or `RUNNING` run with `202 Accepted` and the same `Location`.
- If no duplicate exists and the soft queue cap is full, the API returns `429 sync-queue-full` with `Retry-After` set to the worker claim interval (`asset-sync.sync.worker.fixed-delay`, 5 seconds by default), rounded up to whole seconds.
- The worker claims due `QUEUED` rows, marks them `RUNNING`, and processes provider pages outside database transactions.
- Each watched address has a `sync_cursors` row. The worker acquires that cursor lease, heartbeats it during page work, fetches a bounded provider page, ingests all page events, then advances the checkpoint with lease/version/unexpired-lease fencing.
- `asset-sync.sync.provider-timeout` is the deadline for one provider page fetch.
- Ingest each event through the same observed-event ingestion path used by the API.
- Mark the sync run `SUCCEEDED`, `FAILED`, or requeue it as `QUEUED` in a short fenced transaction.
- `eventsSeen` and `eventsChanged` are cumulative across all claims and healthy continuations for the same sync run.
- Retrying a sync is safe because observed event ingestion is idempotent. `sync_runs` records are diagnostic and are not business idempotency keys.

Failure behavior:

- Provider timeout or provider unavailability no longer bubbles to POST. The worker stores concise failure detail in `lastError` and either requeues the run with backoff or marks it `FAILED` after max attempts.
- Healthy page limits and account traversal limits requeue the same run as a continuation without consuming retry budget.
- HTTP 429 throttling is retryable provider backpressure. A valid `Retry-After` value influences the next attempt delay.
- Every HTTP bridge request carries the address's durable checkpoint as `fromBlockHeight` and `fromEventIndex`, so a bridge that returned a final page without a cursor resumes from there instead of from the start of its history. See the HTTP bridge page contract in `docs/architecture.md`.
- Events committed before a provider failure remain valid.
- The API must not report provider completion from POST; clients poll `GET /api/v1/sync-runs/{id}`.

## 12. Start Account Sync

Enqueues sync for all active watched addresses under one account. The POST behavior is the same as address sync: validate account existence, create or reuse an in-flight run, and return `202 Accepted` with a pollable location.

Account sync uses per-address cursors and a pass over the account's active addresses in `(created_at, id)` order. The pass keeps its keyset in the run checkpoint, so a run whose addresses do not fit one claim resumes where the previous claim stopped and completes once every address has been synced; addresses registered or disabled between claims neither shift it nor get visited twice. An address whose cursor is busy because a direct address sync owns it is deferred and revisited after the scan, and an address with provider pages left keeps the pass on it until it is drained.

Request:

```http
POST /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1/sync
```

Response:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/sync-runs/53059d5b-4813-4d6d-9f8e-6f993744e879
```

```json
{
  "id": "53059d5b-4813-4d6d-9f8e-6f993744e879",
  "targetType": "ACCOUNT",
  "targetId": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
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

Behavior:

- Resolve active watched addresses in bounded pages.
- For each address, acquire the per-address cursor lease and fetch bounded provider pages until the page stream is done or a configured continuation limit is reached.
- Ingest each provider event independently and checkpoint only after the full provider page is ingested.
- A retryable provider failure requeues the overall sync run unless max attempts has been reached.
- A terminal failure of one address (provider data invalid, provider configuration, a database constraint) ends only that address; the pass goes on with the others. When the pass completes, a run with such failures is `FAILED` and its `lastError` reads `<n> of <m> addresses failed terminally: <addressId>: <error>; ...`, capped at the stored error length. Disable an address that keeps failing with `PATCH /api/v1/addresses/{addressId}`.
- Accounts over the configured address cap are terminal `FAILED` during worker execution.

## 13. Get Sync Run

Request:

```http
GET /api/v1/sync-runs/067bdcd7-23c9-44c5-ac73-caeef65ca5ab
```

Response:

```json
{
  "id": "067bdcd7-23c9-44c5-ac73-caeef65ca5ab",
  "targetType": "ADDRESS",
  "targetId": "6df29db1-96d2-4665-8945-266c7f90138e",
  "status": "SUCCEEDED",
  "eventsSeen": 5,
  "eventsChanged": 2,
  "lastError": null,
  "queuedAt": "2026-06-19T00:00:00Z",
  "startedAt": "2026-06-19T00:00:00Z",
  "finishedAt": "2026-06-19T00:00:02Z",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:02Z"
}
```

Possible statuses are `QUEUED`, `RUNNING`, `SUCCEEDED`, and `FAILED`. Legacy `STARTED` may be visible for pre-async rows until recovery or an operator runbook drains them. `startedAt` is nullable while a run is still `QUEUED`.

`lastError` carries the service's own failure messages, such as `Provider timeout after PT10S.`, bounded to `asset-sync.sync.worker.max-error-length`. A database failure shows only its class, for example `Database error (DataIntegrityViolationException).`, and any other unexpected failure shows `Unexpected error (<class>).`, because their messages can quote SQL; the full detail is logged as `sync_run_failure_detail`.

## 14. ProblemDetail Error Mapping

All errors produced by the API layer use `ProblemDetail`, including framework-level routing failures such as unknown paths, unsupported methods, and unsupported content types. The `type` field is a stable service-owned URI. Implementations may add properties for correlation and domain identifiers, but must not expose internal stack traces. Under the protected profiles, `401` and `403` are produced by the Spring Security filter chain before a request reaches Spring MVC; a dedicated authentication entry point and access-denied handler write the same `ProblemDetail` shape, including `requestId`, and `401` responses keep the `WWW-Authenticate: Basic` challenge. A request with credentials that arrives while PostgreSQL is unavailable gets `503 database-unavailable` from the entry point instead, without a challenge, because the credentials could not be checked; `docs/failure-modes.md` section 6 describes the outage.

A request that Spring Security's `StrictHttpFirewall` rejects before authentication, for example one with `//` or `;` in its path, never reaches the API layer. It gets `400` from the servlet container's error page in every profile, with Spring Boot's default error body instead of a `ProblemDetail` and without a Basic challenge.

Common mappings:

| Condition | HTTP status | Problem type |
| --- | ---: | --- |
| Malformed JSON, invalid field type, or a field string longer than 100 000 characters | 400 | `https://asset-sync-service/errors/invalid-request` |
| Bean validation failure | 400 | `https://asset-sync-service/errors/validation-failed` |
| Invalid enum value | 400 | `https://asset-sync-service/errors/validation-failed` |
| Missing required request parameter | 400 | `https://asset-sync-service/errors/invalid-request` |
| Watched address pagination outside the supported bounds | 400 | `https://asset-sync-service/errors/invalid-pagination` |
| Address or transaction hash malformed for its chain | 400 | `https://asset-sync-service/errors/invalid-request` |
| Missing or invalid HTTP Basic credentials in protected profiles | 401 | `https://asset-sync-service/errors/unauthorized` |
| Authenticated caller without the required role in protected profiles | 403 | `https://asset-sync-service/errors/forbidden` |
| Account, watched address, unsupported chain or asset, or sync run not found | 404 | `https://asset-sync-service/errors/not-found` |
| Unknown route | 404 | `https://asset-sync-service/errors/not-found` |
| Unsupported request method, with an `Allow` header | 405 | `https://asset-sync-service/errors/method-not-allowed` |
| Unsupported request content type | 415 | `https://asset-sync-service/errors/unsupported-media-type` |
| Duplicate account `externalRef` | 409 | `https://asset-sync-service/errors/duplicate-account` |
| Duplicate watched address | 409 | `https://asset-sync-service/errors/duplicate-watched-address` |
| Immutable observed transaction conflict | 409 | `https://asset-sync-service/errors/immutable-field-conflict` |
| Database constraint violation from non-HTTP ingest paths | 400 | `https://asset-sync-service/errors/database-constraint-violation` |
| Sync queue is full, with `Retry-After` | 429 | `https://asset-sync-service/errors/sync-queue-full` |
| Provider timeout or unavailable during async execution | Stored on sync run | n/a |
| PostgreSQL unavailable, also while checking HTTP Basic credentials | 503 | `https://asset-sync-service/errors/database-unavailable` |
| Unexpected server failure | 500 | `https://asset-sync-service/errors/internal-error` |
| Any other Spring MVC error response, for example `406 Not Acceptable` | native status | `https://asset-sync-service/errors/<status-name>`, for example `not-acceptable` |

Example:

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

Responses echo `X-Request-Id` when supplied, or generate and return one when absent. The request id is stored in logging MDC for the servlet request. Background sync worker logs use sync-run and worker identifiers because provider work no longer runs inside the original HTTP request.

## 15. Future Extensions

Deferred API capabilities:

- Endpoints to inspect or reset the per-address provider cursors, which only SQL shows today.
- Scheduled sync management endpoints.
- Multi-tenant authorization and account ownership.
- Balance projection read APIs.
- Publisher-specific diagnostics for Kafka, SQS, or CDC-based delivery.
- Rich pagination, sorting, and filtering for operational views.

These extensions must preserve PostgreSQL as the source of truth for observed transaction lifecycle state.
