# Alchemy Provider Runbook

Operating `asset-sync-service` with `asset-sync.provider.type=alchemy`: getting a key, rolling out on Ethereum Sepolia, running the env-gated live smoke, reading what the service records, and handling the failures the adapter reports. Configuration keys and the page-building algorithm are described in `README.md` (Chain Provider) and `docs/architecture.md` (Alchemy Provider Page Building).

## 1. Getting An API Key

1. Create an Alchemy account at <https://dashboard.alchemy.com> (the free tier needs no card and covers development and testnet smoke: 30M compute units per month, 500 CU/s at the time of writing).
2. Create an app: chain **Ethereum**, network **Sepolia**. The app's API key is shown on the app page (`API key` button). Only that key is needed; the service builds `https://eth-sepolia.g.alchemy.com/v2/` itself and sends the key as `Authorization: Bearer <key>` (header auth mode, the default).
3. Put the key into `ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY` in the deployment's secret store or shell. Never write it into YAML, docs, tickets, or logs; the service redacts it from every log line, exception, health detail, checkpoint, and `sync_runs.last_error`.
4. To rotate the key, create a new key in the dashboard, restart the service with the new value, then revoke the old key. The startup preflight probes every required network with the new key, so a bad rotation stops the process before the worker runs.

Mainnet is deliberately not mapped and the seeded `eth-mainnet` chain and asset rows are disabled. Enabling it needs an `asset-sync.provider.alchemy.networks.eth-mainnet` mapping in YAML, a start-block strategy (`registration-safe` avoids a historical backfill), a compute budget review, and the CU/s ceiling of the plan in `rate-limit-capacity` / `rate-limit-refill-per-second`.

## 2. Environment

| Variable | Value for the Sepolia rollout |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` (or `e2e` for the smoke test) |
| `ASSET_SYNC_PROVIDER_TYPE` | `alchemy` |
| `ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY` | the key from section 1 |
| `ASSET_SYNC_PROVIDER_ALCHEMY_AUTH_MODE` | leave `header`; `path` puts the key into request URLs and exists only for compatibility |
| `ASSET_SYNC_PROVIDER_ALCHEMY_START_MODE` | `registration-safe` (new addresses start at the current safe block, no backfill) or `configured-block` with `ASSET_SYNC_PROVIDER_ALCHEMY_ETH_SEPOLIA_START_BLOCK` for a bounded historical backfill |
| `ASSET_SYNC_PROVIDER_BASE_URL` | not needed |

The other Alchemy settings keep their defaults: `safe` finality, `max-window-blocks=5000`, `max-rpc-calls-per-fetch=6`, token bucket 6 burst / 3 per second.

## 3. Database Preparation

The seeded `local-evm` chain is enabled and has no Alchemy network, so the first `alchemy` boot on a fresh database applies the migrations and then stops with `enabled chains with enabled asset configs but no Alchemy network mapping: [local-evm]`. Run the operator step and restart:

```sql
UPDATE chain_configs SET enabled = false WHERE chain_id = 'local-evm';
```

On an existing database also run the rollout preflight query from `docs/database.md` (section 2, changeset 015): every active watched address on an enabled chain must have an enabled asset config, otherwise the startup preflight reports the `(chain_id, asset)` pairs and refuses to start. `eth-sepolia` with `required_confirmations=1` and its enabled `USDC` row (`0x1c7d4b196cb0c7b01d743fbc6116a902379c7238`, 6 decimals) are seeded by the migrations.

## 4. Live Smoke On Sepolia

The smoke is the env-gated test `AlchemyLiveSmokeTests`; CI skips it because `ALCHEMY_LIVE_SMOKE` is unset there. It boots the `e2e` profile with `type=alchemy` against a Testcontainers PostgreSQL, registers the address, drives the worker itself, and asserts the checklist below. It needs Docker and an address with Sepolia USDC history (send yourself testnet USDC from Circle's faucet at <https://faucet.circle.com>, or pick an active holder from the token's page on Sepolia Etherscan).

```bash
ALCHEMY_LIVE_SMOKE=true \
ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY='<key>' \
ALCHEMY_LIVE_SMOKE_ADDRESS='0x...' \
ALCHEMY_LIVE_SMOKE_FROM_BLOCK='<a block shortly before the address's first USDC transfer>' \
./gradlew test --tests 'com.example.assetsync.e2e.AlchemyLiveSmokeTests'
```

- `ALCHEMY_LIVE_SMOKE_FROM_BLOCK` switches the smoke to `configured-block` so a known historical range proves real events are ingested. Keep it a few thousand blocks before the first transfer: every 5000 blocks cost two `alchemy_getAssetTransfers` calls, and the test gives up after 50 worker claims. Without it the default `registration-safe` start makes the first sync idle by design (`eventsSeen=0` is a pass).
- Optional: `ALCHEMY_LIVE_SMOKE_CHAIN_ID` (default `eth-sepolia`, the only mapped chain) and `ALCHEMY_LIVE_SMOKE_ASSET` (default `USDC`).
- The second test in the class boots once with an intentionally invalid key and expects the scrubbed `startup probe failed ... HTTP 401` failure. It runs against the real endpoint, so it costs one request.
- Gradle tracks the `ALCHEMY_LIVE_SMOKE*` variables as test inputs, so changing the gate or the target re-runs the test; to repeat an unchanged, already successful smoke add `--rerun` to the command.

The smoke asserts, and prints as `ALCHEMY_LIVE_SMOKE summary`:

1. The sync run is `SUCCEEDED` with no `failure_attempts` and a null `lastError`.
2. `sync_cursors.provider_cursor` is `{"v":1,"p":"alchemy","nextBlock":N}`, no `pageKey` anywhere, `last_finalized_block_height` populated, high-water populated when events were emitted.
3. Every `observed_transactions` row is the registry asset for the watched address, with a decimal-adjusted amount at scale 18 and a non-negative log-index `event_index`.
4. An idle resync succeeds, duplicates nothing, and keeps the cursor.
5. No `sync_runs.last_error` contains the key.

The same flow works by hand against a running service: `POST /api/v1/accounts`, `POST /api/v1/accounts/{accountId}/addresses` with `chainId=eth-sepolia` and `asset=USDC`, `POST /api/v1/addresses/{addressId}/sync`, then `GET /api/v1/sync-runs/{id}` until `SUCCEEDED`, and the SQL below.

## 5. What To Inspect

```sql
SELECT status, events_seen, failure_attempts, continuation_count, last_requeue_reason, last_error
FROM sync_runs ORDER BY queued_at DESC LIMIT 5;

SELECT provider_cursor, last_processed_block_height, last_processed_event_index, last_finalized_block_height,
       checkpoint->'scan' AS scan, checkpoint->>'finalityFallback' AS finality_fallback
FROM sync_cursors;

SELECT block_height, event_index, direction, amount, status FROM observed_transactions ORDER BY block_height, event_index;
```

- `/actuator/health` (authenticated) shows `alchemyChainProvider` with `provider`, `authMode`, `networks`, `state` (`probe-succeeded`, `fetch-succeeded`, `fetch-failed`) and the scrubbed `error`; it never shows an endpoint or the key.
- Logs: `alchemy_preflight_succeeded` at startup, `alchemy_provider_page_fetch_succeeded` per page (mode, blocks, events, RPC calls, fallbacks), `alchemy_rpc_failed` and `alchemy_provider_page_fetch_failed` on errors, `alchemy_finality_tag_unavailable` once per network when the tag falls back to depth.
- Cost: an idle address costs about 30 CU for the two head calls plus two transfer calls (120 CU each) per 5000-block window; dense regions cost one extra call per block that had to be drained alone (`scan.oneBlockFallbacks` in the checkpoint).
- Meters on `/actuator/prometheus`: `asset.sync.provider.alchemy.rpc` and `asset.sync.provider.alchemy.rpc.duration` per network, method, and result, `asset.sync.provider.alchemy.block.fallbacks`, and `asset.sync.provider.alchemy.skipped.rows` per reason; a rising `UNAVAILABLE` or `CONFIGURATION` result count is the earliest signal of provider trouble.

## 6. Failures And Actions

| Symptom | Meaning | Action |
| --- | --- | --- |
| Startup: `api-key must be set` | no key in the environment | set `ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY` |
| Startup: `startup probe failed ... HTTP 401` or `403` | rejected key, inactive app, or allowlist | check the app in the dashboard; rotate the key |
| Startup: `no Alchemy network mapping: [...]` | an enabled chain with enabled assets is not mapped | disable the chain (section 3) or add the mapping in YAML |
| Startup: `active watched addresses without an enabled asset config` | legacy rows | seed or enable the asset configs, or deactivate the addresses |
| Run `FAILED`, `last_error` `HTTP 401/403` or `JSON-RPC error -32600` | credentials rejected at fetch time (terminal, no retries spent) | fix the key and re-enqueue the sync |
| Run `FAILED`, `Alchemy block N has M ERC20 events ... exceeding request.limit` | one block holds more events than the page size (terminal) | raise `ASSET_SYNC_PAGINATION_PAGE_SIZE` above M and re-enqueue; never clear the cursor to skip the block |
| Run `QUEUED` with `failure_attempts` growing, `last_error` `budget exhausted before block N was fully drained` | the block needs more calls than `max-rpc-calls-per-fetch` allows within the provider timeout | raise `ASSET_SYNC_PROVIDER_ALCHEMY_MAX_RPC_CALLS_PER_FETCH` and, if needed, `ASSET_SYNC_SYNC_PROVIDER_TIMEOUT` (keep it below the cursor lease of 2m); `nextBlock` still points at that block |
| `last_error` `HTTP 429` | provider throttling; `Retry-After` is honored | lower `rate-limit-refill-per-second` or the number of watched addresses, or raise the plan |
| `last_error` `repeated a pageKey` / `repeated transfer` / `returned block X for a Y..Y request` | provider pagination violation (retryable) | nothing; the retry rescans from the same cursor; investigate if it persists |
| `finality_fallback = true` in the checkpoint | the `safe` or `finalized` tag is unavailable on that network | acceptable; the frontier is latest minus `finality-depth-fallback` |
| Run `FAILED`, `last_error` `Alchemy transfer ... not a hex quantity` or `uniqueId does not match` | provider data the adapter cannot map honestly (terminal) | keep the cursor, capture the response shape, and fix the mapper before re-enqueueing |

Rules that never change: do not persist a `pageKey` or a mid-block position in `provider_cursor`, do not clear a cursor to skip a block, and do not put the key into a URL unless `path` auth mode is unavoidable.
