# Architecture

## Data layer

Two modules implement the contract in `core/domain` (`SessionRepository`, `MonitorRepository`,
`KumaNotificationRepository`, `ServerInfoRepository`):

| Module | Kind | Content |
|---|---|---|
| `:core:kuma-client` | pure JVM | `KumaClient` (Socket.IO for one server) behind the `KumaApi` interface, typed `KumaEvent`s, DTOs + mappers to `core:model`, ack/error mapping (`KumaAcks`), `NotificationConfig` |
| `:core:data` | Android library | `KumaConnectionManager` (socket lifecycle + live sync), Room cache, DataStore config, Keystore `SecretStore`, repositories, Hilt bindings (`DataModule`) |

### Connection lifecycle (`KumaConnectionManager`)

```
setForeground(true) ──> open socket ──> server: info (partial) + loginRequired
                                         └─> loginByToken(stored JWT)
                                              ├─ ok      -> burst: monitorList, info (full), maintenanceList,
                                              │             notificationList, heartbeatList x N, uptime/avgPing/certInfo
                                              │             -> Room; state Connected(serverInfo)
                                              └─ ok:false -> token cleared, state LoginRequired, socket closed
setForeground(false) ─> socket closed after 30 s grace (cancelled by returning to foreground)
connection lost ──────> Socket.IO reconnects with backoff (1 s .. 30 s); every reconnect re-runs loginByToken
refresh event ────────> token cleared, LoginRequired
```

- `ConnectionState`: `NotConfigured` (no server) -> `Connecting` -> `Connected(info)`; `Failed(NETWORK)` while
  connection attempts fail (Socket.IO keeps retrying); `Failed(UNSUPPORTED_SERVER)` when the post-login `info.version`
  is not `2.x` (other 2.x minors only log a warning); `Disconnected` after background close.
- One-off calls (`loadBeats`, `pause`, `addNotification`, ...) run through `withApi`: they reuse the live socket or,
  in background, open a transient one ("lease"), log in by token and close it again when the last lease ends.
  Without a stored token they throw `KumaException(UNAUTHORIZED)`.
- `syncOnce(timeout)` (push/WorkManager): returns `false` without config/token or when the token is rejected;
  otherwise connects (transiently in background), waits until `monitorList` plus a `heartbeatList` for every listed
  monitor has been written, lets trailing stats land (300 ms) and disconnects. Timeout -> `KumaException(TIMEOUT)`.
- `login(url, user, password, totp)`: URL normalised (`https://` added when no scheme, query/fragment and trailing
  slashes dropped, sub-path kept), waits for readiness, `needSetup` -> `SETUP_REQUIRED`, `{tokenRequired:true}` ->
  `TwoFactorRequired` (the socket is kept for the TOTP step), version gate on the post-login `info`, clears the cache
  when server or user changed, persists config (DataStore) + JWT (SecretStore) and keeps the socket for immediate data.
  Servers with auth disabled (`autoLogin`) store a marker instead of a JWT.
- `logout()`: runs the `LogoutHook`s first (multibinding; e.g. push removes its Kuma notification while the session
  still works), emits `logout` best effort, then clears token, Room tables and config -> `NotConfigured`.

Data events are queued per socket and applied only after the socket is authenticated, so nothing from a previous or
foreign session leaks into the cache and the Socket.IO thread is never blocked.

### Cache (Room, `kuma-cache.db`, schema v1 in `core/data/schemas`)

| Table | Fed by | Notes |
|---|---|---|
| `monitors`, `monitor_tags` | `monitorList` (replace, missing monitors + their beats/stats deleted), `updateMonitorIntoList` (upsert), `deleteMonitorFromList` | tag definitions seen on monitors also go to `tags` |
| `tags` | `getTags` after every login | |
| `heartbeats` | `heartbeatList` (overwrite -> replace, else merge), `heartbeat` (append) | key `(monitorId, timeMillis)` because live beats have no row id; ring buffer: newest 150 per monitor plus newest 500 important ones |
| `uptime` | `uptime` (24 / 720 / "1y"), `avgPing` | fractions 0..1 |
| `cert_info` | `certInfo` (double-encoded JSON) | leaf certificate only |
| `notifications` | `notificationList` | raw `config` JSON kept; parsed into `KumaNotification.fields` on read |
| `maintenance_monitors` | `MonitorJSON.maintenance` flags, then `maintenanceList` | `maintenanceList` has no monitor ids, so every `under-maintenance` entry is resolved with `getMonitorMaintenance`; children of a covered group count as covered. Status-3 heartbeats show maintenance regardless |

`observeMonitors()` joins all tables into `MonitorOverview` (last 100 beats, status of the newest beat), sorted like
Kuma's `monitorList`: **weight descending, then name (case-insensitive), then id**. The list is flat; group children
follow the same rule and carry `parentId`, so the UI can nest them.

Secrets: `SecretStore` (public interface in `:core:data`) encrypts values with an Android Keystore AES-256-GCM key
(random IV, entry key as associated data) into a private SharedPreferences file; the push module can store its ntfy
token there. Non-secret config lives in the `kuma_settings` DataStore. Both are excluded from backups by the app's
`data_extraction_rules.xml`.

### Kuma pitfalls handled in `:core:kuma-client`

- Nothing is emitted before `loginRequired`/`autoLogin` (Kuma registers its handlers only after `sendInfo`; earlier
  emits are silently dropped). `KumaClient.awaitReady()` gates every request.
- No `Origin` header on the WebSocket upgrade (Kuma rejects a mismatching one). WebSocket first; if the very first
  attempt fails the client retries once with polling + upgrade.
- A base URL sub-path becomes the engine path (`/kuma/socket.io/`); Socket.IO would otherwise treat it as namespace.
- Acks: `{ok:false}` -> `SERVER_REJECTED`, `authIncorrectCreds`/`authInvalidToken`/`authUserInactiveOrDeleted` ->
  `INVALID_CREDENTIALS`, "Too frequently" -> `RATE_LIMITED`, "You are not logged in." -> `UNAUTHORIZED`, no ack in time
  -> `TIMEOUT`, socket lost while waiting -> `NETWORK`. Bare-boolean (`needSetup`) and empty (`logout`) acks are
  supported; `{tokenRequired:true}` without `ok` means 2FA.
- Lenient parsing: ids as numbers or strings (`monitorList` keys, `heartbeatList`/`uptime`/`avgPing` ids), booleans as
  `0/1` (`heartbeatList` rows, paged important beats), missing `ping` on maintenance beats, optional `duration`,
  `uptime` period `"1y"` as string, `avgPing` `null`, `certInfo` as JSON string, `notification.config` as JSON string.
- Times: heartbeat `time` is `"YYYY-MM-DD HH:mm:ss.SSS"` UTC without zone -> epoch millis; chart buckets are unix
  seconds and arrive unsorted (sorted client side); cert `valid_from` uses Node's `"Sep 26 22:49:11 2026 GMT"` format.
- `addNotification`/`testNotification` payloads are built like the web UI's dialog: provider fields plus `name`,
  `type`, `isDefault`, `active`, `applyExisting` in one flat object.

### Tests

- `./gradlew :core:kuma-client:test` and `:core:data:testDebugUnitTest`: fixture-driven (`fixtures/kuma-2.5.8`, path
  passed as system property `kuma.fixtures`), no network.
- `./gradlew :core:kuma-client:integrationTest` and `./gradlew :core:data:testDebugUnitTest -Pintegration`: talk to the
  dev Kuma from `infra/docker-compose.dev.yml` (seeded with `infra/kuma-seed`). Override with
  `KUMA_URL`/`KUMA_USER`/`KUMA_PASSWORD`.
