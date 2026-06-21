# API Specification

Status: Current MVP API  
Scope: MVP REST API  
Source of truth: `docs/architecture.md`

## 1. Versioning And Conventions

All MVP endpoints are exposed under `/api/v1`. The version is part of the URL because the API is intended to be consumed by external systems and must support future incompatible changes without content negotiation ambiguity.

Conventions:

- Request and response bodies use JSON.
- Timestamps use UTC ISO-8601 strings.
- Identifiers use UUID strings.
- Monetary amounts are encoded as decimal strings and stored with `numeric(38, 18)` precision.
- Enum values use upper snake case, for example `SEEN`, `CONFIRMED`, and `INBOUND`.
- API DTOs are mapped to application commands before use-case execution.
- Controllers do not depend on jOOQ or database-generated classes.
- Errors use Spring `ProblemDetail` with `application/problem+json`.

Compatibility rules:

- Additive response fields are allowed in `/api/v1`.
- Existing field semantics, enum names, and status code meanings are stable within `/api/v1`.
- Breaking changes require a new URL version.

## 2. MVP Endpoints

### Accounts

```text
POST /api/v1/accounts
GET  /api/v1/accounts/{accountId}
```

### Watched Addresses

```text
POST /api/v1/accounts/{accountId}/addresses
GET  /api/v1/accounts/{accountId}/addresses
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

Deferred read endpoints, not exposed by the current Phase 10 implementation:

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
- `address` is required and must be non-blank.
- `asset` is required and must be non-blank.
- `label` is optional; if provided, it must be non-blank after trimming.
- Duplicate `chainId + address + asset` registrations are rejected with `409 Conflict`.

Address normalization is chain-specific and is not expanded in the MVP beyond trimming and preserving the exact stored string. If stronger normalization is introduced later, it must be applied before uniqueness checks.

## 6. List Watched Addresses

Request:

```http
GET /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1/addresses
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
  ]
}
```

## 7. Ingest Observed Event

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
- `eventIndex` is required and must be `>= 0`.
- `amount` is required, must parse as a non-negative decimal, and must fit `numeric(38, 18)`.
- `blockHeight` is required and must be `>= 0`.
- `confirmations` is required and must be `>= 0`.
- `direction` must be `INBOUND` or `OUTBOUND`.
- `status` must be `SEEN`, `CONFIRMED`, or `REVERTED`.
- The watched address identified by `chainId + address + asset` must exist and be active.

Idempotency behavior:

- The natural idempotency key is `chainId + txHash + eventIndex + address + asset`.
- If no row exists, the service creates an observed transaction and creates one lifecycle outbox event for the resulting status.
- If a row exists, the service locks it with `FOR UPDATE`, evaluates the domain transition, and writes only meaningful changes.
- Exact duplicates return `NO_CHANGE` and create no outbox event.
- Stale events return `NO_CHANGE` and create no outbox event.
- Immutable field conflicts return `409 Conflict`.
- `REVERTED` is terminal in the MVP.

## 8. Deferred Transaction List Endpoint

The current Phase 10 implementation does not expose transaction listing/read endpoints. The response shape below is retained as a deferred design target.

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

## 9. Deferred Get Transaction Endpoint

The current Phase 10 implementation does not expose this endpoint.

Request:

```http
GET /api/v1/transactions/5e1c9c94-6e36-4fb9-bb27-67800e88ac51
```

Response shape is the same transaction object used by the list endpoint.

## 10. Start Address Sync

Starts a sync for one watched address using the MVP fake chain provider. The provider call must not run inside a database transaction.

Request:

```http
POST /api/v1/addresses/6df29db1-96d2-4665-8945-266c7f90138e/sync
```

Response:

```http
HTTP/1.1 202 Accepted
```

```json
{
  "id": "067bdcd7-23c9-44c5-ac73-caeef65ca5ab",
  "targetType": "ADDRESS",
  "targetId": "6df29db1-96d2-4665-8945-266c7f90138e",
  "status": "SUCCEEDED",
  "eventsSeen": 5,
  "eventsChanged": 2,
  "lastError": null,
  "startedAt": "2026-06-19T00:00:00Z",
  "finishedAt": "2026-06-19T00:00:02Z",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:02Z"
}
```

Behavior:

- Create a `sync_runs` row in a short transaction.
- Fetch events from the fake provider outside a database transaction.
- Ingest each event through the same observed-event ingestion path used by the API.
- Mark the sync run `SUCCEEDED` or `FAILED` in a short transaction.
- Return `202 Accepted` with the current sync run state. The MVP may complete the fake-provider sync before the response is returned.
- Retrying a sync is safe because observed event ingestion is idempotent. `sync_runs` records are diagnostic and are not business idempotency keys.

Failure behavior:

- Provider timeout or provider unavailability marks the sync run `FAILED` and returns a `503 Service Unavailable` response.
- Events committed before a provider failure remain valid.
- The API must not report success if the final sync run state is `FAILED`.

## 11. Start Account Sync

Starts sync for all active watched addresses under one account.

Request:

```http
POST /api/v1/accounts/4f6f3d3a-40b5-46fd-86cc-7105d19f17d1/sync
```

Response:

```http
HTTP/1.1 202 Accepted
```

```json
{
  "id": "53059d5b-4813-4d6d-9f8e-6f993744e879",
  "targetType": "ACCOUNT",
  "targetId": "4f6f3d3a-40b5-46fd-86cc-7105d19f17d1",
  "status": "SUCCEEDED",
  "eventsSeen": 12,
  "eventsChanged": 4,
  "lastError": null,
  "startedAt": "2026-06-19T00:00:00Z",
  "finishedAt": "2026-06-19T00:00:04Z",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:04Z"
}
```

Behavior:

- Resolve active watched addresses at the start of the sync.
- Call the fake provider once per watched address.
- Ingest each provider event independently.
- A provider failure should fail the overall sync run unless implementation explicitly records partial success in a later version.

## 12. Get Sync Run

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
  "startedAt": "2026-06-19T00:00:00Z",
  "finishedAt": "2026-06-19T00:00:02Z",
  "createdAt": "2026-06-19T00:00:00Z",
  "updatedAt": "2026-06-19T00:00:02Z"
}
```

## 13. ProblemDetail Error Mapping

All errors use `ProblemDetail`. The `type` field is a stable service-owned URI. Implementations may add properties for correlation and domain identifiers, but must not expose internal stack traces.

Common mappings:

| Condition | HTTP status | Problem type |
| --- | ---: | --- |
| Malformed JSON or invalid field type | 400 | `https://asset-sync-service/errors/invalid-request` |
| Bean validation failure | 400 | `https://asset-sync-service/errors/validation-failed` |
| Invalid enum value | 400 | `https://asset-sync-service/errors/validation-failed` |
| Account, watched address, unsupported chain, or sync run not found | 404 | `https://asset-sync-service/errors/not-found` |
| Duplicate account `externalRef` | 409 | `https://asset-sync-service/errors/duplicate-account` |
| Duplicate watched address | 409 | `https://asset-sync-service/errors/duplicate-watched-address` |
| Immutable observed transaction conflict | 409 | `https://asset-sync-service/errors/immutable-field-conflict` |
| Provider timeout or unavailable | 503 | `https://asset-sync-service/errors/provider-unavailable` |
| PostgreSQL unavailable | 503 | `https://asset-sync-service/errors/database-unavailable` |

Example:

```json
{
  "type": "https://asset-sync-service/errors/immutable-field-conflict",
  "title": "Immutable observed transaction field conflict",
  "status": 409,
  "detail": "Observed transaction natural key matched an existing row, but amount or direction did not match.",
  "instance": "/api/v1/observed-events",
  "chainId": "local-evm",
  "txHash": "0xdeadbeef",
  "eventIndex": 0
}
```

## 14. Future Extensions

Deferred API capabilities:

- Real provider cursor management.
- Scheduled sync management endpoints.
- Multi-tenant authorization and account ownership.
- Balance projection read APIs.
- Publisher-specific diagnostics for Kafka, SQS, or CDC-based delivery.
- Rich pagination, sorting, and filtering for operational views.

These extensions must preserve PostgreSQL as the source of truth for observed transaction lifecycle state.
