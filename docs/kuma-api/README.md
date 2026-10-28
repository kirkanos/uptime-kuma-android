# Uptime Kuma internal API reference (for the Android client)

Analysed version: **Uptime Kuma `2.5.8`** (git tag `2.5.8`, commit `0bd417b981d7dd4e7a0ecaa5afcd90c274d74eca`, committed 2026-10-27).
Latest 2.5.x tags at analysis time: 2.5.0 ... 2.5.8. All `file:line` references in these documents point into that tag of
`https://github.com/louislam/uptime-kuma`. The source was only read, never executed.

| Document | Content |
|---|---|
| [`socket-events.md`](socket-events.md) | Socket.IO transport, connect/login flow, JWT, rate limits, every client->server event with args/ack/side effects, every server->client event with payload shape, `HeartbeatJSON`, `MonitorJSON`, `CertInfo` |
| [`rest-endpoints.md`](rest-endpoints.md) | All Express routes: entry page, `/api/push`, badges, status-page JSON/RSS/manifest/incident history, `/metrics` auth |
| [`monitor-types.md`](monitor-types.md) + [`../../spec/monitor-types.json`](../../spec/monitor-types.json) | 34 monitor types, 117-field catalogue with visibility conditions/defaults/DB columns, `add`/`editMonitor` payload rules, conditions schema |
| [`notification-providers.md`](notification-providers.md) + [`../../spec/notification-providers.json`](../../spec/notification-providers.json) | 108 notification provider type ids, labels, categories, per-provider form fields; deep dive on `ntfy` and `webhook`; message/template format |
| [`maintenance-and-status-pages.md`](maintenance-and-status-pages.md) | Maintenance strategies and payload, status page config/groups/incidents, public shapes |

## Constants

| Name | Value | Source |
|---|---|---|
| Heartbeat status | `DOWN=0`, `UP=1`, `PENDING=2`, `MAINTENANCE=3` | `src/util.ts:32-35` |
| Status page overall status | `ALL_DOWN=0`, `ALL_UP=1`, `PARTIAL_DOWN=2`, `MAINTENANCE=3` | `src/util.ts:37-40` |
| `uptime` event period keys | `24` (24 h), `720` (30 d), `"1y"` (1 year) - note: numbers for the first two, a **string** for the third | `server/model/monitor.js:1330-1338` |
| Date formats | `SQL_DATETIME_FORMAT = "YYYY-MM-DD HH:mm:ss"`, heartbeat `time` = `"YYYY-MM-DD HH:mm:ss.SSS"` (UTC, no zone) | `src/util.ts:43`, `server/model/monitor.js:460` |
| Min interval | 1 s (`MIN_INTERVAL_SECOND`), UI warns below 20 s | `src/util.ts:46`, `src/pages/EditMonitor.vue:4325-4336` |
| Ping limits | packet size 1..65500 (default 56), global timeout 1..300 s (default 10), count 1..100 (default 1; UI 3), per-request timeout 1..60 s (default 2) | `src/util.ts:50-68` |
| Response body saving | default 1024 bytes, max 1 MiB | `src/util.ts:74-79` |
| Incident page size | 10 | `src/util.ts:48` |
| Heartbeat list sizes | `heartbeatList` push: last 100; web UI keeps max 150 in memory; important events paged (UI 25/page) | `server/client.js:46-64`, `src/mixins/socket.js:211-213` |
| Push token | 32 chars `[A-Za-z0-9]`, client generated | `src/pages/EditMonitor.vue:3292`, `src/util.ts:536-544` |
| API key format | `uk<id>_<40-char nanoid>` | `server/socket-handlers/api-key-socket-handler.js:22-32` |
| Rate limits | login 20/min, 2FA 30/min, API 60/min (global, token bucket) | `server/rate-limiter.js:61-80` |
| Default TLS/domain expiry notify days | `[7, 14, 21]` | `server/util-server.js:940-945`, `src/pages/Settings.vue:180-186` |
| Default data retention | `keepDataPeriodDays = 180` (heartbeats older than that are deleted by a background job) | `src/pages/Settings.vue:172-174`, `server/jobs/clear-old-data.js` |
| Badge colours | up `#66c20a`, warn `#eed202`, down `#c2290a`, pending `#f8a306`, maintenance `#1747f5`, n/a `#999` | `src/util.ts:144-162` |
| Socket.IO | server `socket.io ~4.8.3`, default path `/socket.io/`, default namespace | `package.json`, `server/uptime-kuma-server.js:148` |

## Known pitfalls for clients

1. **WebSocket origin check.** When the client sends an `Origin` header on the WS upgrade that does not match the `Host` header, the connection is refused (`server/uptime-kuma-server.js:163-197`). Either send no `Origin` or match the host. Polling transport is never origin-checked.
2. **First `info` is partial.** On connect `info` lacks `version`, `latestVersion`, `isContainer`, `dbType`, `runtime` (hidden until login) (`server/client.js:145-163`). Wait for the post-login `info` before using `info.runtime`.
3. **JWT never expires** by itself; it is invalidated only by a password change (hash claim `h`) or user deactivation. `loginByToken` is the way to resume; on `{ok:false}` discard the token. Password change / re-enabling auth emits `refresh` and disconnects the socket.
4. **Login quirks.** `login` with no callback or empty data is silently ignored; 2FA is signalled by `{tokenRequired:true}` **without** `ok`; every attempt consumes one of 20 tokens/minute globally.
5. **`heartbeatList` rows are raw DB rows (snake_case: `monitor_id`, `down_count`, `end_time`) while `heartbeat` and `monitorImportantHeartbeatListPaged` use camelCase `HeartbeatJSON` (`monitorID`)**. `heartbeatList` is oldest-first, max 100, sent for every monitor right after login (potentially hundreds of events) and again after reconnect+loginByToken. Treat `overwrite=true` as "replace", otherwise prepend.
6. **`important` semantics** (`server/model/monitor.js:1390-1416`): first beat ever, UP<->DOWN transitions, PENDING->DOWN, and any transition into/out of MAINTENANCE are important. UP->PENDING and PENDING->UP are **not**. Notifications fire on a subset (`isImportantForNotification`, `:1425-1448`): first beat only if DOWN (`:1458`), UP->DOWN, DOWN->UP, PENDING->DOWN, MAINTENANCE->DOWN. `clearEvents` rewrites `important=0` and `msg=''` on all beats of a monitor.
7. **Time strings** (`time`, `end_time`, `createdDate`, maintenance `dateRange`) carry no timezone. Heartbeat/incident times are UTC; render with the device zone. Maintenance `dateRange`/`timeRange` are wall-clock in the maintenance `timezone`. `info.serverTimezone`/`serverTimezoneOffset` give the server zone.
8. **`uptime` values are fractions 0..1**, badges multiply by 100. `avgPing` may be `null`.
9. **`certInfo` payload is a JSON string**, not an object; `domainInfo` third argument is a serialised `Date`.
10. **`monitorList` / `updateMonitorIntoList` / `maintenanceList` / `statusPageList` are objects keyed by id (string keys)**, not arrays. `notificationList`, `proxyList`, `dockerHostList`, `remoteBrowserList`, `apiKeyList` are arrays.
11. **`notificationList` entries** expose the provider config only as the JSON string `config`; `isDefault`/`active` are booleans but `is_default` (0/1) is also present. Parse `config` to get `type` and provider fields.
12. **`add` requires `accepted_statuscodes` (string array) for every type** and chokes on unknown properties (SQL error) - never echo `MonitorJSON` read-only fields into `add`. `editMonitor` ignores unknown properties but only writes its explicit list (no `active`, `weight`, `tags`, `snmpV3Username`).
13. **Tags are not part of the monitor payload**; use `addTag`/`addMonitorTag`/`editMonitorTag`/`deleteMonitorTag` after the monitor exists.
14. **Status page REST JSON is cached** 5 min (`/api/status-page/:slug`) and 1 min (heartbeat endpoint); the cache is cleared on important beats and admin changes, so polling faster than that returns stale data.
15. **Maintenance list is global per server**, not filtered by user (`getMaintenanceJSONList`), and is re-pushed on every important heartbeat - expect frequent `maintenanceList` events.
16. `uploadBackup` exists in the web client but has **no server handler**; `importantHeartbeatList` exists on the server but is **never emitted**.
17. **Every monitor-mutating event also pushes `updateMonitorIntoList`** with the full `MonitorJSON` (including secrets). Keep the local map in sync from that event rather than from the ack.
18. Group monitors: `MonitorJSON.active` is already combined with parents (`forceInactive` tells you a parent is paused); `childrenIDs` is recursive; `pathName` is `"Parent / Child"`.
19. Ack messages with `msgi18n:true` are i18n keys (e.g. `successAdded`, `Saved.`, `authIncorrectCreds`); the client needs its own translations. `msg` can also be an object `{key, values}` (`testChrome`).

## Tooling

The spec JSON files were generated with scripts kept outside the repo (`kuma-spec-tools/extract_notification_fields.py`,
`gen_notification_spec.py`, `gen_monitor_types.py`), which scan the Kuma sources as data. Re-run them against a newer tag to diff.
