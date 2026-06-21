# Domain State Machine Specification

Status: Draft  
Scope: MVP observed transaction lifecycle  
Source of truth: `docs/architecture.md`

## 1. Purpose

The domain state machine determines how an incoming observed event changes the stored transaction lifecycle. It is independent from Spring, HTTP, persistence, scheduling, and provider implementation details.

The state machine input is a current transaction snapshot, or `NONE` when no row exists, plus an incoming observed event and chain confirmation configuration. The output is a deterministic transition result used by the application service to update PostgreSQL and create outbox events.

## 2. Domain Enums

### `TransactionStatus`

```text
SEEN
CONFIRMED
REVERTED
```

Meaning:

- `SEEN`: transaction event has been observed but has not reached final confirmation threshold.
- `CONFIRMED`: transaction event reached confirmation threshold or provider explicitly reported confirmation.
- `REVERTED`: transaction event was invalidated by reorg or provider reversion signal.

`REVERTED` is terminal in the MVP.

### `Direction`

```text
INBOUND
OUTBOUND
```

Meaning:

- `INBOUND`: value is moving toward the watched address.
- `OUTBOUND`: value is moving away from the watched address.

### `OutboxEventType`

```text
TRANSACTION_SEEN
TRANSACTION_CONFIRMED
TRANSACTION_REVERTED
```

Outbox events are created only for meaningful lifecycle stages. Duplicate, stale, and conflict outcomes do not create outbox events.

## 3. Input Fields

Immutable identity and value fields:

- `chainId`
- `txHash`
- `eventIndex`
- `address`
- `asset`
- `direction`
- `amount`

Mutable lifecycle fields:

- `blockHeight`
- `confirmations`
- `status`

Configuration:

- `requiredConfirmations` from `chain_configs.required_confirmations`.

## 4. Transition Outcomes

The domain layer returns one of these outcomes:

| Outcome | Meaning | Persistence action |
| --- | --- | --- |
| `Created` | No current row existed and the event should be stored | Insert observed transaction |
| `Updated` | Current row exists and lifecycle state changed meaningfully | Update observed transaction |
| `NoChange` | Duplicate or stale event | No lifecycle write except optional diagnostics such as `lastSeenAt` if the application chooses |
| `Conflict` | Natural key matched but immutable fields differ | Reject with domain conflict |

The application layer maps `Conflict` to `409 Conflict`.

## 5. Full Transition Table

Legend:

- `incomingEffectiveStatus` is the incoming provider status after applying the confirmation threshold rule.
- `sameImmutableFields` means all immutable fields match the stored row.
- `higherConfirmations` means incoming confirmations are greater than stored confirmations.
- `sameOrLowerConfirmations` means incoming confirmations are less than or equal to stored confirmations.

| Current status | Incoming effective status | Condition | Result status | Outcome | Outbox event |
| --- | --- | --- | --- | --- | --- |
| `NONE` | `SEEN` | Valid event below threshold | `SEEN` | `Created` | `TRANSACTION_SEEN` |
| `NONE` | `CONFIRMED` | Provider says confirmed or threshold reached | `CONFIRMED` | `Created` | `TRANSACTION_CONFIRMED` |
| `NONE` | `REVERTED` | Provider sends reorg/reverted signal before any stored event | `REVERTED` | `Created` | `TRANSACTION_REVERTED` |
| `SEEN` | `SEEN` | Immutable fields match and confirmations increase below threshold | `SEEN` | `Updated` | none |
| `SEEN` | `SEEN` | Immutable fields match and event is duplicate or stale | `SEEN` | `NoChange` | none |
| `SEEN` | `CONFIRMED` | Immutable fields match and threshold reached or provider says confirmed | `CONFIRMED` | `Updated` | `TRANSACTION_CONFIRMED` |
| `SEEN` | `REVERTED` | Immutable fields match and provider says reverted | `REVERTED` | `Updated` | `TRANSACTION_REVERTED` |
| `CONFIRMED` | `SEEN` | Immutable fields match and event is older or stale | `CONFIRMED` | `NoChange` | none |
| `CONFIRMED` | `CONFIRMED` | Immutable fields match and confirmations increase | `CONFIRMED` | `Updated` | none |
| `CONFIRMED` | `CONFIRMED` | Immutable fields match and duplicate confirmation | `CONFIRMED` | `NoChange` | none |
| `CONFIRMED` | `REVERTED` | Immutable fields match and provider says reverted | `REVERTED` | `Updated` | `TRANSACTION_REVERTED` |
| `REVERTED` | `SEEN` | Immutable fields match | `REVERTED` | `NoChange` | none |
| `REVERTED` | `CONFIRMED` | Immutable fields match | `REVERTED` | `NoChange` | none |
| `REVERTED` | `REVERTED` | Immutable fields match | `REVERTED` | `NoChange` | none |
| any existing row | any | Immutable fields conflict | unchanged | `Conflict` | none |

Required transition coverage from architecture:

```text
NONE -> SEEN
NONE -> CONFIRMED
NONE -> REVERTED
SEEN -> SEEN
SEEN -> CONFIRMED
SEEN -> REVERTED
CONFIRMED -> CONFIRMED
CONFIRMED -> REVERTED
REVERTED -> REVERTED
```

The table also specifies stale `SEEN` and `CONFIRMED` inputs after `REVERTED` as no-ops because `REVERTED` is terminal in the MVP.

## 6. Confirmation Threshold Rules

Effective incoming status:

```text
if incoming.status == REVERTED:
  REVERTED
else if incoming.status == CONFIRMED:
  CONFIRMED
else if incoming.confirmations >= requiredConfirmations:
  CONFIRMED
else:
  SEEN
```

Rules:

- `requiredConfirmations` is loaded from `chain_configs`.
- `confirmations` must be non-negative.
- A threshold of `0` means a valid non-reverted event is immediately eligible for `CONFIRMED`.
- Stored confirmations are monotonic for non-reverted rows.
- Lower incoming confirmations do not reduce stored confirmations.
- Higher confirmations on an existing `SEEN` row may update `confirmations`, `blockHeight`, `lastSeenAt`, and `updatedAt` even when status remains `SEEN`.
- Higher confirmations on a `CONFIRMED` row may update `confirmations`, `lastSeenAt`, and `updatedAt` but must not emit another `TRANSACTION_CONFIRMED` event.

## 7. Reorg Handling

Reorg signal:

- Incoming provider status `REVERTED`.

Rules:

- `REVERTED` wins over `SEEN` and `CONFIRMED`.
- `SEEN -> REVERTED` is allowed.
- `CONFIRMED -> REVERTED` is allowed.
- `NONE -> REVERTED` is allowed to preserve a provider-observed reorg fact.
- `REVERTED -> REVERTED` is a duplicate/no-op.
- `REVERTED` is terminal in the MVP.

When a transaction becomes `REVERTED`:

- Set `status = REVERTED`.
- Set `revertedAt` if it is not already set.
- Preserve `confirmedAt` if the transaction was previously confirmed.
- Create `TRANSACTION_REVERTED` once.

## 8. Immutable Field Conflict Rules

The natural key is:

```text
chainId + txHash + eventIndex + address + asset
```

When an existing row matches the natural key, these fields must also match exactly:

- `direction`
- `amount`

The fields that form the natural key are already fixed by the lookup and database constraint. A mismatch in `direction` or `amount` is a provider/data conflict and must be rejected.

Conflict behavior:

- Return a domain `Conflict` outcome.
- Do not update the observed transaction.
- Do not create an outbox event.
- The API maps the conflict to `409 Conflict`.
- Structured diagnostics should include `chainId`, `txHash`, `eventIndex`, `address`, and `asset`.

## 9. Stale Event Behavior

Stale events are accepted as safe no-ops when immutable fields match.

Examples:

- Incoming `SEEN` after stored `CONFIRMED`.
- Incoming lower confirmation count after a higher stored count.
- Duplicate `CONFIRMED` after stored `CONFIRMED`.
- Any incoming non-reverted status after stored `REVERTED`.
- Duplicate `REVERTED` after stored `REVERTED`.

Stale no-op behavior:

- Return `NoChange`.
- Do not create an outbox event.
- Do not lower `confirmations`.
- Do not move `CONFIRMED` back to `SEEN`.
- Do not move `REVERTED` back to `SEEN` or `CONFIRMED`.

The application may update diagnostic timestamps only if that does not create misleading lifecycle semantics. The compact MVP can keep no-op processing write-free.

## 10. Outbox Event Creation Rules

Outbox events are emitted for lifecycle stage creation or state changes:

| Resulting lifecycle event | When created |
| --- | --- |
| `TRANSACTION_SEEN` | `NONE -> SEEN` |
| `TRANSACTION_CONFIRMED` | `NONE -> CONFIRMED` or `SEEN -> CONFIRMED` |
| `TRANSACTION_REVERTED` | `NONE -> REVERTED`, `SEEN -> REVERTED`, or `CONFIRMED -> REVERTED` |

No outbox event is created for:

- Duplicate observed event.
- Higher confirmations that keep status `SEEN`.
- Higher confirmations that keep status `CONFIRMED`.
- Stale event.
- Immutable field conflict.

Outbox idempotency key:

```text
observed-tx:{chainId}:{txHash}:{eventIndex}:{address}:{asset}:status:{newStatus}
```

The observed transaction change and outbox insertion must happen in the same database transaction.

## 11. Future Extensions

Deferred state-machine extensions:

- Non-terminal reappearance after `REVERTED`.
- Chain-specific finality models beyond confirmation counts.
- Provider confidence levels.
- Reconciliation jobs that compare provider state over block ranges.
- Balance projection derived from transaction state.

These extensions must keep the core transition logic testable without Spring.
