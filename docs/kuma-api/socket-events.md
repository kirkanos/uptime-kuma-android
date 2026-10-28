# Uptime Kuma 2.5.8 - Socket.IO API

Source of truth: `louislam/uptime-kuma` tag `2.5.8` (commit `0bd417b981d7dd4e7a0ecaa5afcd90c274d74eca`).
All paths below are relative to the Kuma repository root. `file:line` references point into that tag.

Conventions used in this document:

- `C->S` = event emitted by the client, received by the server (`socket.on(...)` in server code).
- `S->C` = event emitted by the server to the client.
- "ack" = the Socket.IO acknowledgement callback (last argument of `emit`). Unless noted, every ack has the shape
  `{ ok: boolean, msg?: string, msgi18n?: boolean, ...extra }`. When `ok === false`, `msg` is the error text.
  When `msgi18n === true`, `msg` is an i18n key from `src/lang/*.json` (or an object `{key, values}` as in `testChrome`).
- "auth" = the handler calls `checkLogin(socket)` (`server/util-server.js:644-648`), which throws `"You are not logged in."`
  when `socket.userID` is not set. The ack is then `{ok:false, msg:"You are not logged in."}`.
- Status codes: `DOWN=0`, `UP=1`, `PENDING=2`, `MAINTENANCE=3` (`src/util.ts:32-35`).

---

## 1. Transport

| Item | Value | Source |
|---|---|---|
| socket.io server version | `~4.8.3` (Engine.IO v4 protocol, "EIO=4") | `package.json` dependencies |
| socket.io-client used by web UI | `~4.8.3` | `package.json` |
| Path | default `/socket.io/` (no `path` option passed to `new Server(...)`) | `server/uptime-kuma-server.js:148-199` |
| Transports | socket.io defaults (`polling`, `websocket`); server inspects `req._query.transport` | `server/uptime-kuma-server.js:150-160` |
| Namespace | default `/` | - |
| CORS | only in development (`origin: "*"`); none in production (same-origin expected) | `server/uptime-kuma-server.js:140-146` |
| WebSocket origin check | for `transport === "websocket"`: allowed if `UPTIME_KUMA_WS_ORIGIN_CHECK=bypass`, or if **no `Origin` header is sent**, or if `Origin` host equals `Host` header (or `X-Forwarded-For` when `trustProxy` setting is on). Otherwise connection is rejected. Polling is always allowed (protected by CORS). | `server/uptime-kuma-server.js:163-197` |
| Client URL | web UI uses `io(undefined)` = same origin; a native client connects to `https://<host>[/basepath]` with path `/socket.io/` | `src/mixins/socket.js:107-120` |
| Default express timeout for axios | n/a to clients | - |

Important for a native Android client: do **not** send an `Origin` header on the WebSocket upgrade (OkHttp/socket.io-client-java does not by default), or send one whose host matches the server's `Host`.
Reverse proxies that strip/alter `Host` can make the check fail ("Origin (...) does not match host (...)").

Rooms: after login the socket joins room `socket.userID` (the numeric user id) (`server/server.js:1834-1835`). Nearly all pushes use
`io.to(userID).emit(...)`, i.e. **all sockets of the same user receive the same events** (multi-device).

---

## 2. Connection and login flow

Server side (`server/server.js:389-1764`):

1. On `connection`, server immediately emits `info` **with version hidden** (`sendInfo(socket, true)`, `server/client.js:145-163`).
2. If no user exists yet (`needSetup`), server emits `setup` (no payload) (`server/server.js:392-395`; `needSetup` computed in `initDatabase`, `server/server.js:1892-1895`).
3. All handlers are registered.
4. If setting `disableAuth` is true: server runs `afterLogin(socket, firstUser)` and emits `autoLogin` (`server/server.js:1756-1759`).
   Else it emits `loginRequired` (`server/server.js:1761`).
5. Client then calls `loginByToken` (if it has a stored JWT) or `login`.
6. `afterLogin` (`server/server.js:1833-1865`) sets `socket.userID`, joins room, and pushes in this order:
   `monitorList`, then in parallel `info` (full), `maintenanceList`, `notificationList`, `proxyList`, `dockerHostList`,
   `apiKeyList`, `remoteBrowserList`, `monitorTypeList`; then `statusPageList`; then for **every** monitor:
   `heartbeatList` (last 100 beats) and `Monitor.sendStats` (= `avgPing`, `uptime` x3, `certInfo`, `domainInfo`);
   finally `initServerTimezone` if the setting `initServerTimezone` was never set.

Client reference implementation: `src/mixins/socket.js:122-301` (listeners), `:412-456` (login/loginByToken), `:462-469` (logout).

### JWT

- Created by `User.createJWT` (`server/model/user.js:41-49`): `jwt.sign({ username, h: shake256(user.password, 16) }, jwtSecret)`.
  **No `exp` claim, no expiry** (no `expiresIn` anywhere in `server/`). Algorithm: jsonwebtoken default HS256.
- `h` = SHAKE256 hex digest (output length 16 bytes) of the stored bcrypt password hash (`server/util-server.js:832-844`).
  A token is invalidated when the password changes (`loginByToken` compares `decoded.h`, `server/server.js:415-417`).
- `jwtSecret` is stored in table `setting` key `jwtSecret` (`server/server.js:1881-1897`).
- The web UI stores the token in `localStorage.token` (remember me) or `sessionStorage.token` and decodes `username` from it (`src/mixins/socket.js:325-340`).

### Rate limits (`server/rate-limiter.js`)

| Limiter | Tokens / interval | Applied to | Error ack |
|---|---|---|---|
| `loginRateLimiter` | 20 per minute (`:61-66`) | `login`, `logout` (socket), HTTP basic auth failures (`server/auth.js:106-123`) | `{ok:false, msg:"Too frequently, try again later."}` (`:39-47`) |
| `twoFaRateLimiter` | 30 per minute (`:75-80`) | `prepare2FA`, `save2FA`, `disable2FA` | same |
| `apiRateLimiter` | 60 per minute (`:68-73`) | HTTP API-key auth (`server/auth.js:79-97`) | HTTP 401 |

Limits are global (per process), not per IP/user. Every `login` attempt consumes a token, successful or not (`server/server.js:465`).

---

## 3. Client -> Server events

### 3.1 Public (no auth)

#### `loginByToken(token: string, ack)` - `server/server.js:401-448`
- Verifies JWT with `server.jwtSecret`, loads `user WHERE username=? AND active=1`, checks `decoded.h`.
- ack success: `{ ok: true }` (no token returned).
- ack failures: `{ok:false, msg:"authUserInactiveOrDeleted", msgi18n:true}` or `{ok:false, msg:"authInvalidToken", msgi18n:true}`.
- Side effects on success: `afterLogin` -> full data push (see section 2).

#### `login(data: {username: string, password: string, token?: string}, ack)` - `server/server.js:450-528`
- Returns silently (no ack!) if `ack` is not a function or `data` is falsy (`:456-462`).
- Rate limited (`loginRateLimiter`).
- `username` is `trim()`ed server-side (`server/auth.js:20`).
- Outcomes:
  - wrong creds: `{ok:false, msg:"authIncorrectCreds", msgi18n:true}`
  - user has `twofa_status === 0`: `afterLogin`, ack `{ok:true, token:<JWT>}`
  - user has 2FA and `data.token` absent: ack `{ tokenRequired: true }` (**no `ok` field**) (`:484-490`)
  - `data.token` present: TOTP verified with `notp.totp.verify(token, secret, {window:1, time:30})` (`:169-172`, `:493`); a token equal to the last used one is rejected (`twofa_last_token`). Success: `{ok:true, token:<JWT>}`; failure: `{ok:false, msg:"authInvalidToken", msgi18n:true}`.
  - Note: if `twofa_status === 0` **and** `data.token` is given, both branches run and the ack may be called twice (`:473-518`). Clients should not send `token` unless `tokenRequired` was received.

#### `logout(ack)` - `server/server.js:530-542`
- Rate limited (`loginRateLimiter`). Leaves the user room, sets `socket.userID = null`. ack is called with **no arguments**.
- The socket stays connected; the client must drop its stored token itself.

#### `needSetup(ack)` - `server/server.js:701-703`
- ack receives a **bare boolean** (`true` = no user exists yet).

#### `setup(username: string, password: string, ack)` - `server/server.js:705-736`
- Creates the first user. Fails with `passwordTooWeak` (i18n) if `check-password-strength` says "Too weak", or with a plain error if a user already exists.
- ack: `{ok:true, msg:"successAdded", msgi18n:true}`.

#### `getWebpushVapidPublicKey(ack)` - `server/server.js:1637-1661` (no `checkLogin`)
- ack `{ok:true, msg:<publicVapidKey string>}`; generates and stores VAPID keys on first call.

#### `getIncidentHistory(slug, cursor: string|null, ack)` - `server/socket-handlers/status-page-socket-handler.js:103-122` (no `checkLogin`; `isPublic = !socket.userID`)
- ack `{ok:true, incidents:[IncidentPublic...], total:number, nextCursor:string|null, hasMore:boolean}` (`server/model/status_page.js:517-556`; page size `INCIDENT_PAGE_SIZE = 10`, `src/util.ts:48`). Cursor = `created_date` of the last item.

#### `disconnectOtherSocketClients()` - `server/socket-handlers/general-socket-handler.js:152-159` (auth, no ack)
- Emits `refresh` to and disconnects all other sockets of the same user.

### 3.2 2FA and account (auth)

| Event | Args | ack success | Notes / source |
|---|---|---|---|
| `prepare2FA` | `(currentPassword, ack)` | `{ok:true, uri:"otpauth://totp/Uptime%20Kuma:<user>?secret=<base32>"}` | rate-limited (2FA); error `2faAlreadyEnabled` (i18n) if already on. Stores secret immediately. `server/server.js:544-585` |
| `save2FA` | `(currentPassword, ack)` | `{ok:true, msg:"2faEnabled", msgi18n:true}` | sets `twofa_status=1`. `:587-615` |
| `disable2FA` | `(currentPassword, ack)` | `{ok:true, msg:"2faDisabled", msgi18n:true}` | `:617-644`, `server/2fa.js:9-11` |
| `verifyToken` | `(token, currentPassword, ack)` | `{ok:true, valid:true}` / `{ok:false, msg:"authInvalidToken", msgi18n:true, valid:false}` | `:646-674` |
| `twoFAStatus` | `(ack)` | `{ok:true, status:boolean}` | `:676-699` |
| `changePassword` | `({currentPassword, newPassword}, ack)` | `{ok:true, token:<new JWT>, msg:"successAuthChangePassword", msgi18n:true}` | validates strength (`passwordTooWeak`), verifies current pw, then **emits `refresh` to and disconnects all other sockets of the user** (`disconnectAllSocketClients`, `server/uptime-kuma-server.js:552-561`). Old JWTs become invalid (`h` changes). `:1451-1481` |

`doubleCheckPassword(socket, currentPassword)` (`server/util-server.js:658-670`) throws `"Wrong data type?"` if not a string and `"Incorrect current password"` on mismatch.

### 3.3 Monitors (auth)

#### `add(monitor: MonitorPayload, ack)` - `server/server.js:743-815`
- `monitor.notificationIDList` (object `{ [notificationId]: true }`) is removed from the payload and written to `monitor_notification` (`updateMonitorNotification`, `:1798-1809`; only keys with truthy values are stored).
- `monitor.accepted_statuscodes` **must be an array of strings**; otherwise error `"Accepted status codes are not all strings"` (`:752-754`). It is stored as `accepted_statuscodes_json`. **If the property is missing the handler throws a TypeError** (`.every` of undefined) -> ack `{ok:false, msg:"Cannot read properties of undefined ..."}`. Always send it, for every type.
- `kafkaProducerBrokers`, `kafkaProducerSaslOptions`, `conditions`, `rabbitmqNodes` are `JSON.stringify`ed (`:758-763`). `conditions` column is `NOT NULL DEFAULT "[]"`; send `conditions: []` when unused.
- Frontend-only props deleted: `humanReadableInterval`, `globalpingdnsresolvetypeoptions`, `responsecheck` (`:769-778`).
- Everything else is imported into the bean with `bean.import(monitor)` (redbean maps camelCase -> snake_case columns). **Unknown properties cause an SQL error**, so never send `id`, `includeSensitiveData`, `maintenance`, `childrenIDs`, `forceInactive`, `path`, `pathName`, `screenshot`, `tags`, `active` (the UI deletes these when cloning, `src/pages/EditMonitor.vue:4094-4119`). Exception: `active: false` is honoured (`:795`) and is a real column.
- `retryOnlyOnStatusCodeFailure` -> `retry_only_on_status_code_failure` (`:782-784`).
- `bean.validate()` (`server/model/monitor.js:1630-1785`) runs: interval and retryInterval >= 1 s, JSON validity of headers/brokers/sasl/rabbitmq/conditions/accepted codes, ping ranges, screenshot_delay, mongodb query JSON, service-name rules.
- Side effects: `updateMonitorIntoList` (to the user room), monitor started unless `monitor.active === false`.
- ack: `{ ok:true, msg:"successAdded", msgi18n:true, monitorID:number }`.

#### `editMonitor(monitor: MonitorPayload, ack)` - `server/server.js:818-998`
- Loads `monitor WHERE id = monitor.id`; `"Permission denied."` if `user_id` differs.
- Rejects a `parent` that is a descendant (`"Invalid Monitor Group"`). Changing type away from `group` unlinks children.
- Only an **explicit list** of fields is copied (`:847-967`); anything else in the payload is ignored. Notable: `tags` are not handled here (use tag events), `active`/`weight` are not editable here, `snmpV3Username` is **not** copied (upstream omission; SNMPv3 username can only be set via `add`), `port` is `parseInt`ed and set to `null` if NaN (`:878-884`), `proxyId` must be an integer else `null` (`:903`).
- `notificationIDList` is replaced wholesale (`:977`).
- If the monitor (and all its parents) is active, it is restarted (`:979-981`).
- Side effect: `updateMonitorIntoList`.
- ack: `{ ok:true, msg:"Saved.", msgi18n:true, monitorID }`.

Full field list and semantics: see `monitor-types.md` and `spec/monitor-types.json`.

#### `getMonitorList(ack)` - `:1000-1014` -> re-emits `monitorList` to the user room; ack `{ok:true}`.

#### `getMonitor(monitorID, ack)` - `:1016-1035`
- ack `{ok:true, monitor: MonitorJSON}` (`toJSON(preloadData)` **with sensitive data**, `server/model/monitor.js:116-264`). Error if not owned.

#### `checkDomain(partial: {type, url, hostname, grpcUrl}, ack)` - `:1038-1056`
- ack `{ok:true, domain, tld}` or `{ok:false, msg:<i18n key>, msgi18n:true, meta:{...}}` (`server/model/domain_expiry.js:211-245`).

#### `getMonitorBeats(monitorID, period: number /*hours*/, ack)` - `:1058-1091`
- `period == null` -> `"Invalid period."`.
- SQL: `SELECT * FROM heartbeat WHERE monitor_id=? AND time > now - period hours ORDER BY time ASC` (`Database.sqlHourOffset`, `server/database.js:830-836`).
- ack `{ok:true, data: HeartbeatRow[]}` where rows are **raw DB rows (snake_case)**: `id, monitor_id, status, msg, time, ping, important, duration, down_count, retries, end_time, response`. No ownership check on `monitorID`.
- Not used by the 2.5.8 web UI (it uses `getMonitorChartData`).

#### `getMonitorChartData(monitorID, period: number /*hours*/, ack)` - `server/socket-handlers/chart-socket-handler.js:6-37`
- `period <= 24` -> minutely buckets (`period*60` points), `<= 720` -> hourly, else daily (`period/24` points) (`UptimeCalculator.getDataArray`, `server/uptime-calculator.js:697-766`).
- ack `{ok:true, data: Array<{ up:number, down:number, avgPing:number, minPing:number, maxPing:number, timestamp:number /*unix seconds, bucket start*/, maintenance?:number, ...extras }>}` (buckets with no data are omitted). Periods used by the UI: 2, 3, 6, 24, 168, 720 h (`src/components/PingChart.vue`).

#### `resumeMonitor(monitorID, ack)` / `pauseMonitor(monitorID, ack)` - `:1094-1130`
- Ownership checked (`checkOwner`, `:1818-1824`). Sets `active = 1/0`, starts/stops, emits `updateMonitorIntoList`.
- ack `{ok:true, msg:"successResumed"|"successPaused", msgi18n:true}`.

#### `deleteMonitor(monitorID, deleteChildren?: boolean, ack)` - `:1132-1220`
- Backward compatible: `deleteMonitor(monitorID, ack)` also works (`:1135-1138`).
- For groups: `deleteChildren=true` deletes descendants recursively (each emits `deleteMonitorFromList`), else children are unlinked (`parent=null`) and each child gets `updateMonitorIntoList`.
- Clears the status-page API cache. ack `{ok:true, msg:"successDeleted", msgi18n:true}` **then** emits `deleteMonitorFromList(monitorID)`.

#### `clearEvents(monitorID, ack)` - `:1663-1680`: `UPDATE heartbeat SET msg='', important=0 WHERE monitor_id=?`; ack `{ok:true}`. No `heartbeatList` re-push.
#### `clearHeartbeats(monitorID, ack)` - `:1682-1708`: `UptimeCalculator.clearStatistics` (deletes heartbeats + stats), restarts the monitor if active, emits `heartbeatList(monitorID, [], overwrite=true)` to the user room; ack `{ok:true}`.
#### `clearStatistics(ack)` - `:1710-1735`: clears all statistics of all monitors, restarts active monitors; ack `{ok:true}`.

### 3.4 Important heartbeats / events (auth)

There is **no** `importantHeartbeatList` push in 2.5.8: `sendImportantHeartbeatList` exists in `server/client.js:74-97` but is never called, and the web UI has no listener. Paging is done with:

#### `monitorImportantHeartbeatListCount(monitorID: number|null, ack)` - `server/server.js:1386-1407`
- `monitorID == null` -> count over **all monitors** (`important = 1`), used by the dashboard home.
- ack `{ok:true, count:number}`.

#### `monitorImportantHeartbeatListPaged(monitorID: number|null, offset: number, count: number, ack)` - `:1409-1449`
- `... important = 1 ORDER BY time DESC LIMIT count OFFSET offset`.
- ack `{ok:true, data: HeartbeatJSON[]}` (redbean beans serialised through `Heartbeat.toJSON`, camelCase; see section 5). No ownership check.
- UI page size: 25 (`src/pages/Details.vue:484`, `src/pages/DashboardHome.vue:137`).

### 3.5 Tags (auth) - `server/server.js:1222-1384`

| Event | Args | ack |
|---|---|---|
| `getTags` | `(ack)` | `{ok:true, tags:[{id, name, color}]}` (all tags, not per user) |
| `addTag` | `({name, color}, ack)` | `{ok:true, tag:{id,name,color}}` |
| `editTag` | `({id, name, color}, ack)` | `{ok:true, msg:"Saved.", msgi18n:true, tag}` or `{ok:false, msg:"tagNotFound", msgi18n:true}` |
| `deleteTag` | `(tagID, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}` (cascades to `monitor_tag`) |
| `addMonitorTag` | `(tagID, monitorID, value: string, ack)` | `{ok:true, msg:"successAdded", msgi18n:true}` + `updateMonitorIntoList` |
| `editMonitorTag` | `(tagID, monitorID, value, ack)` | `{ok:true, msg:"successEdited", msgi18n:true}` + `updateMonitorIntoList` (updates all rows with that tag/monitor) |
| `deleteMonitorTag` | `(tagID, monitorID, value, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}` + `updateMonitorIntoList` (row matched by `value` too) |

`color` is any CSS colour string (UI offers hex presets). The same tag may be attached to a monitor multiple times with different `value`s.
UI flow when saving a monitor (`src/components/TagsManager.vue:318-390`): for each new tag -> `addTag` (if no id) then `addMonitorTag`; for each removed -> `deleteMonitorTag`.

### 3.6 Notifications (auth) - `server/server.js:1567-1635`

| Event | Args | ack / notes |
|---|---|---|
| `addNotification` | `(notification: object, notificationID: number\|null, ack)` | add when id is null, else edit. Whole object is stored as JSON in `notification.config`; `name`, `isDefault` and `applyExisting` (one-shot, not persisted) are also interpreted (`server/notification.js:273-301`). ack `{ok:true, msg:"Saved.", msgi18n:true, id}`; then `notificationList` pushed. |
| `deleteNotification` | `(notificationID, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}`; then `notificationList` pushed. |
| `testNotification` | `(notification: object, ack)` | sends `Notification.send(notification, notification.name + " Testing")` with `monitorJSON=null, heartbeatJSON=null`; ack `{ok:true, msg:<provider ok message, e.g. "Sent Successfully.">}` or `{ok:false, msg:<error>}` |
| `checkApprise` | `(ack)` | ack receives **bare boolean** (apprise CLI present?) |

### 3.7 Settings (auth)

#### `getSettings(ack)` - `server/server.js:1483-1502`
- ack `{ok:true, data: {...}}` where `data` = all rows of `setting WHERE type='general'` parsed as JSON (`server/settings.js:89-103`) plus `serverTimezone` filled from `server.getTimezone()` if missing.
- Keys used by UI/server (`src/pages/Settings.vue:156-193`, `src/components/settings/*.vue`): `checkUpdate` (bool, default true), `checkBeta` (bool), `searchEngineIndex` (bool, default false), `entryPage` (`"dashboard"` or `"statusPage-<slug>"`), `primaryBaseURL` (string), `steamAPIKey`, `globalpingApiToken`, `nscd` (bool, default true), `chromeExecutable`, `serverTimezone` (IANA tz), `trustProxy` (bool), `keepDataPeriodDays` (number, default 180), `tlsExpiryNotifyDays` (number[], default `[7,14,21]`), `domainExpiryNotifyDays` (number[], default `[7,14,21]`), `disableAuth` (bool). Server-only keys also live in the table (`jwtSecret`, `apiKeysEnabled`, `initServerTimezone`, `webpush*VapidKey`, `cloudflaredTunnelToken`, ...) but have a different `type` so are not returned.

#### `setSettings(data: object, currentPassword: string|undefined, ack)` - `:1504-1564`
- `currentPassword` is required (verified) only when switching `disableAuth` from false to true. Enabling auth again disconnects all other sockets (`refresh`).
- Writes every key in `data` (type `general`), applies `entryPage`, `serverTimezone`, Chrome reset, nscd.
- ack `{ok:true, msg:"Saved.", msgi18n:true}`; then emits `info` and `maintenanceList`.

#### `initServerTimezone(timezone: string)` - `server/socket-handlers/general-socket-handler.js:45-55` (no ack)
- Response to the `initServerTimezone` S->C event: sets the server timezone once and re-emits `info`.

### 3.8 Status pages (auth unless noted) - `server/socket-handlers/status-page-socket-handler.js`

See `maintenance-and-status-pages.md` for payload shapes.

| Event | Args | ack |
|---|---|---|
| `getStatusPage` | `(slug, ack)` | `{ok:true, config: StatusPageJSON}` (`:268-288`) |
| `addStatusPage` | `(title, slug, ack)` | `{ok:true, msg:"successAdded", msgi18n:true, slug}`; slug lowercased, must match `^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$` (`:436-479`, `:533-547`) |
| `saveStatusPage` | `(slug, config, imgDataUrl: string, publicGroupList: Group[], ack)` | `{ok:true, publicGroupList}` (groups now carry ids) (`:292-433`) |
| `deleteStatusPage` | `(slug, ack)` | `{ok:true}` (`:482-523`) |
| `postIncident` | `(slug, incident:{id?, title, content, style}, ack)` | `{ok:true, incident: IncidentPublic}` (`:34-82`) |
| `editIncident` | `(slug, incidentID, incident:{title, content, style, pin?}, ack)` | `{ok:true, msg:"Saved.", msgi18n:true, incident}` (`:124-185`) |
| `deleteIncident` | `(slug, incidentID, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}` (`:187-225`) |
| `resolveIncident` | `(slug, incidentID, ack)` | `{ok:true, msg:"Resolved", msgi18n:true, incident}` (`:227-266`) |
| `unpinIncident` | `(slug, ack)` | `{ok:true}` - unpins all pinned incidents of that page (`:84-101`) |
| `getIncidentHistory` | `(slug, cursor, ack)` | public, see 3.1 |

### 3.9 Maintenance (auth) - `server/socket-handlers/maintenance-socket-handler.js`

| Event | Args | ack |
|---|---|---|
| `addMaintenance` | `(maintenance, ack)` | `{ok:true, msg:"successAdded", msgi18n:true, maintenanceID}`; pushes `maintenanceList` (`:16-43`) |
| `editMaintenance` | `(maintenance /*with id*/, ack)` | `{ok:true, msg:"Saved.", msgi18n:true, maintenanceID}`; `"Permission denied."` if not owner (`:46-74`) |
| `addMonitorMaintenance` | `(maintenanceID, monitors:[{id}], ack)` | replaces the monitor set; `{ok:true, msg:"successAdded", msgi18n:true}` (`:77-106`) |
| `addMaintenanceStatusPage` | `(maintenanceID, statusPages:[{id}], ack)` | replaces the status-page set (`:109-138`) |
| `getMaintenance` | `(maintenanceID, ack)` | `{ok:true, maintenance: MaintenanceJSON}` (`:140-158`) |
| `getMaintenanceList` | `(ack)` | `{ok:true}` + pushes `maintenanceList` (`:160-174`) |
| `getMonitorMaintenance` | `(maintenanceID, ack)` | `{ok:true, monitors:[{id}]}` (`:176-198`) |
| `getMaintenanceStatusPage` | `(maintenanceID, ack)` | `{ok:true, statusPages:[{id, title}]}` (`:200-222`) |
| `deleteMaintenance` | `(maintenanceID, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}` then `maintenanceList` (`:224-252`) |
| `pauseMaintenance` | `(maintenanceID, ack)` | `{ok:true, msg:"successPaused", msgi18n:true}` then `maintenanceList` (`:254-285`) |
| `resumeMaintenance` | `(maintenanceID, ack)` | `{ok:true, msg:"successResumed", msgi18n:true}` then `maintenanceList` (`:287-318`) |

### 3.10 Proxies, Docker hosts, remote browsers, API keys (auth)

| Event | Args | ack / notes | Source |
|---|---|---|---|
| `addProxy` | `(proxy, proxyID\|null, ack)` | `{ok:true, msg:"Saved.", msgi18n:true, id}`; pushes `proxyList`; if `proxy.applyExisting` also attaches to all monitors and re-pushes `monitorList`. `proxy = {protocol, host, port, auth:boolean, username, password, active, default, applyExisting}`; protocol in `https, http, socks, socks5, socks5h, socks4` (`src/components/ProxyDialog.vue:15-21`, `server/proxy.js:20-62`) | `server/socket-handlers/proxy-socket-handler.js:13-37` |
| `deleteProxy` | `(proxyID, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}`; pushes `proxyList` | `:39-58` |
| `addDockerHost` | `(dockerHost:{name, dockerType:"socket"\|"tcp", dockerDaemon}, dockerHostID\|null, ack)` | `{ok:true, msg:"Saved.", msgi18n:true, id}`; pushes `dockerHostList` | `docker-socket-handler.js:12-31`, `server/docker.js:21-42` |
| `deleteDockerHost` | `(dockerHostID, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}`; monitors referencing it get `docker_host=null` | `:33-51` |
| `testDockerHost` | `(dockerHost, ack)` | `{ok:true, msg:"Connected Successfully. Amount of containers: N"}` | `:53-78` |
| `addRemoteBrowser` | `(remoteBrowser:{name, url}, remoteBrowserID\|null, ack)` | `{ok:true, msg:"Saved.", msgi18n:true, id}`; pushes `remoteBrowserList` | `remote-browser-socket-handler.js:14-33` |
| `deleteRemoteBrowser` | `(id, ack)` | `{ok:true, msg:"successDeleted", msgi18n:true}` | `:35-53` |
| `testRemoteBrowser` | `(remoteBrowser, ack)` | `{ok:true, msg:"Connected Successfully."}` | `:55-78` |
| `addAPIKey` | `(key:{name, expires: "YYYY-MM-DD HH:mm"\|null, active?}, ack)` | `{ok:true, msg:"successAdded", msgi18n:true, key:"uk<id>_<40-char nanoid>", keyID}`; the clear key is shown **once**; sets setting `apiKeysEnabled=true`; pushes `apiKeyList` | `api-key-socket-handler.js:18-52` |
| `getAPIKeyList` | `(ack)` | `{ok:true}` + `apiKeyList` | `:54-68` |
| `deleteAPIKey` / `disableAPIKey` / `enableAPIKey` | `(keyID, ack)` | `{ok:true, msg:"successDeleted"\|"successDisabled"\|"successEnabled", msgi18n:true}` + `apiKeyList` | `:70-143` |

### 3.11 Misc (auth)

| Event | Args | ack | Source |
|---|---|---|---|
| `getGameList` | `(ack)` | `{ok:true, gameList:[{keys:[id], pretty, options:{port,...}, extra}]}` (GameDig games) | `general-socket-handler.js:57-70` |
| `getPM2ProcessList` | `(ack)` | `{ok:true, processList:[{id, name, status}]}` | `:72-85` |
| `testChrome` | `(executable, ack)` | `{ok:true, msg:{key:"foundChromiumVersion", values:[version]}, msgi18n:true}` | `:87-114` |
| `getPushExample` | `(language, ack)` | `{ok:true, code:string}` (`language` must match `/^[a-z-]+$/`) | `:116-149` |
| `getDatabaseSize` | `(ack)` | `{ok:true, size:number /*bytes*/}` | `database-socket-handler.js:11-24` |
| `shrinkDatabase` | `(ack)` | `{ok:true}` (VACUUM) | `:26-39` |
| `cloudflared_join` / `cloudflared_leave` | `()` | no ack; join emits `cloudflared_installed`, `cloudflared_running`, `cloudflared_token` to the user room | `cloudflared-socket-handler.js:36-55` |
| `cloudflared_start` | `(token)` | no ack | `:57-70` |
| `cloudflared_stop` | `(currentPassword, ack)` | ack only on error `{ok:false, msg}` | `:72-86` |
| `cloudflared_removeToken` | `()` | no ack | `:88-95` |

Client-only leftovers: the web UI still defines `uploadBackup` (`src/mixins/socket.js:679-681`) but **the server has no handler** in 2.5.8 (never acked).

---

## 4. Server -> Client events

| Event | Payload | When | Source |
|---|---|---|---|
| `info` | `{ primaryBaseURL: string\|null, serverTimezone: string, serverTimezoneOffset: "+HH:mm", version?: string, latestVersion?: string\|null, isContainer?: boolean, dbType?: "sqlite"\|"mariadb", runtime?: {platform: string, arch: string} }` | on connect (without version & runtime), after login, after `setSettings`, after `initServerTimezone` | `server/client.js:145-163` |
| `setup` | none | on connect when no user exists | `server/server.js:392-395` |
| `loginRequired` | none | on connect when auth enabled | `:1761` |
| `autoLogin` | none | on connect when `disableAuth` is set (socket is already logged in) | `:1759` |
| `monitorList` | `{ [monitorId: string]: MonitorJSON }` (object keyed by id, ordered `weight DESC, name`) | after login, `getMonitorList`, `addProxy` with applyExisting | `server/uptime-kuma-server.js:223-281` |
| `updateMonitorIntoList` | `{ [monitorId]: MonitorJSON }` (one or more entries; merge into local map) | after add/edit/pause/resume, tag changes, child unlink on group delete | `:235-240` |
| `deleteMonitorFromList` | `monitorID: number` | after delete | `:248-250` |
| `monitorTypeList` | `{ [type]: { supportsConditions: boolean, conditionVariables: [{id, operators:[{id, caption}]}] } }` for the 26 server-registered types | after login | `server/client.js:214-236` |
| `heartbeat` | `HeartbeatJSON` (see section 5) | every check of every monitor of the user; also from `/api/push/:token` | `server/model/monitor.js:1064`, `server/routers/api-router.js:126` |
| `heartbeatList` | `(monitorID: number\|string, beats: HeartbeatRow[], overwrite: boolean)` - **raw DB rows (snake_case)**, oldest first, max 100 | after login for every monitor (overwrite=false); after `clearHeartbeats` (empty, overwrite=true) | `server/client.js:46-64` |
| `importantHeartbeatList` | `(monitorID, HeartbeatJSON[], overwrite)` | **never emitted in 2.5.8** (dead code) | `server/client.js:74-97` |
| `avgPing` | `(monitorID: number, avgPing: number\|null)` - 24h average, 2 decimals | after login and after every heartbeat (`sendStats`), only if a client is in the user room | `server/model/monitor.js:1327` |
| `uptime` | `(monitorID: number, period: 24 \| 720 \| "1y", uptime: number /*0..1 fraction*/)` | with `avgPing` (three emits) | `:1330-1338` |
| `certInfo` | `(monitorID: number, infoJson: string)` - JSON **string** of `{valid: boolean, certInfo: CertInfo, hostnameMatchMonitorUrl?: boolean}` | with `avgPing`, only when a `monitor_tls_info` row exists | `:1357-1362` |
| `domainInfo` | `(monitorID: number, daysRemaining: number, expiresOn: Date /*serialised ISO string*/)` | with `avgPing`, only when domain expiry is known | `:1371-1381` |
| `notificationList` | `NotificationRow[]` = `{id, name, active: boolean, user_id, is_default: 0\|1, config: string /*JSON*/, isDefault: boolean}` (bean export: snake_case columns plus `isDefault`/`active` coerced to booleans) | after login, add/delete notification | `server/client.js:18-36` |
| `proxyList` | `ProxyRow[]` (bean export: `id, user_id, protocol, host, port, auth, username, password, active, default, created_date`; booleans as 0/1 - UI coerces `auth`, `active`, `default`) | after login, add/delete proxy | `:104-116`, `src/mixins/socket.js:186-194` |
| `dockerHostList` | `[{id, userID, dockerDaemon, dockerType, name}]` | after login, add/delete | `:170-185`, `server/model/docker_host.js:8-16` |
| `remoteBrowserList` | `[{id, url, name}]` | after login, add/delete | `:192-207` |
| `apiKeyList` | `[{id, name, userID, createdDate, active, expires, status:"active"\|"inactive"\|"expired"}]` (no key hash) | after login, key changes | `:123-137`, `server/model/api_key.js:42-52` |
| `maintenanceList` | `{ [maintenanceId]: MaintenanceJSON }` (all maintenances in memory, not filtered by user) | after login, maintenance changes, `setSettings`, every important heartbeat, maintenance window start/end | `server/uptime-kuma-server.js:288-314` |
| `statusPageList` | `{ [statusPageId]: StatusPageJSON }` (all pages, ordered by title) | after login | `server/model/status_page.js:366-377` |
| `initServerTimezone` | none - client should reply with `initServerTimezone(<IANA tz guess>)` | after login when setting `initServerTimezone` unset | `server/server.js:1861-1864` |
| `refresh` | none - web UI does `location.reload()`; token may now be invalid | password change, re-enabling auth, `disconnectOtherSocketClients` | `server/uptime-kuma-server.js:552-561` |
| `cloudflared_installed` / `cloudflared_running` / `cloudflared_message` / `cloudflared_errorMessage` / `cloudflared_token` | boolean / boolean / string / string / string | cloudflared room members | `cloudflared-socket-handler.js:16-28, 40-42` |

Web-UI client behaviour worth copying (`src/mixins/socket.js`):
- `heartbeat`: append to per-monitor list; cap at 150 entries (shift oldest) (`:204-213`); if `important` and status 0/1 show toast.
- `heartbeatList`: if the monitor has no list yet or `overwrite`, replace; else **prepend** (`data.concat(existing)`) (`:236-242`).
- On re-`connect` (connectCount >= 2) all heartbeat lists are cleared because the server re-sends them after `loginByToken` (`:274-286`, `:666-669`).
- `info.version` change -> reload (`:871-875`).

---

## 5. Payload shapes

### 5.1 `HeartbeatJSON` (`server/model/heartbeat.js:32-44`) - used by `heartbeat` and `monitorImportantHeartbeatListPaged`

```json
{
  "monitorID": 12,
  "status": 1,
  "time": "2026-10-28 09:15:03.123",
  "msg": "200 - OK",
  "ping": 87,
  "important": false,
  "duration": 60,
  "retries": 0,
  "response": null
}
```

- `time`: `R.isoDateTimeMillis(dayjs.utc())` -> `"YYYY-MM-DD HH:mm:ss.SSS"` in **UTC**, no zone suffix (`server/model/monitor.js:460`, `server/routers/api-router.js:72`).
- `ping`: ms, integer (DB column `heartbeat.ping` is `INTEGER`, widened to `BIGINT` by migration `2026-01-10-0000-convert-float-precision.js:3-5`; `/api/push` accepts floats and stores them as given); `null` for types without latency (e.g. push without `ping` param, manual).
- `duration`: only set for **push** monitors (seconds since the previous beat when the window was missed, `server/model/monitor.js:764,780`; seconds between beats in `/api/push`, `server/routers/api-router.js:80`). For all other types it is the column default `0`. `end_time` (raw rows only) is the timestamp returned by `uptimeCalculator.update`, i.e. the beat time itself (`server/uptime-calculator.js:212-299`).
- `retries`: current retry counter (PENDING logic, `server/model/monitor.js:944-963`).
- `important`: see README "important semantics". `downCount` and `end_time` are **not** in `toJSON` (they are in raw rows).
- `response`: brotli-compressed, base64 encoded response body if `saveResponse/saveErrorResponse` enabled (decoded only in notification `heartbeatJSON`, `:51-63`).
- In notification templates `heartbeatJSON` additionally has `timezone`, `timezoneOffset`, `localDateTime` (`"YYYY-MM-DD HH:mm:ss"` in server tz), `lastDownTime` (UP only) and `msg` defaults to `"N/A"` (`server/model/monitor.js:1470-1508`).

### 5.2 `HeartbeatRow` (raw `SELECT *`) - used by `heartbeatList`, `getMonitorBeats`

Columns (`db/knex_init_db.js:125-148` + migrations): `id, important (0|1), monitor_id, status, msg, time, ping, duration, down_count, retries (2023-09-29 migration), end_time (2023-08-18), response (2025-10-15)`. Booleans are integers 0/1 in SQLite.

### 5.3 `MonitorJSON` (`server/model/monitor.js:116-264`) - used by `monitorList`, `updateMonitorIntoList`, `getMonitor`

Non-sensitive part (always present):
`id, name, description, path: string[] (group names + own name), pathName: "Group / Sub / Name", parent: number|null, childrenIDs: number[], url, wsIgnoreSecWebsocketAcceptHeader: boolean, wsSubprotocol, method, hostname, port, location, protocol, maxretries, weight, active: boolean (own active AND all parents active), forceInactive: boolean (a parent is inactive), type, subtype, timeout, interval, retryInterval, retryOnlyOnStatusCodeFailure: boolean, resendInterval, keyword, invertKeyword: boolean, expiryNotification: boolean, domainExpiryNotification: boolean, ignoreTls: boolean, upsideDown: boolean, packetSize, maxredirects, accepted_statuscodes: string[], dns_resolve_type, dns_resolve_server, dns_last_result, docker_container, docker_host, proxyId, notificationIDList: {[id]: true}, tags: [{tag_id, monitor_id, value, name, color}], maintenance: boolean (currently under maintenance), mqttTopic, mqttSuccessMessage, mqttCheckType, databaseQuery, authMethod, grpcUrl, grpcProtobuf, grpcMethod, grpcServiceName, grpcEnableTls: boolean, radiusCalledStationId, radiusCallingStationId, game, gamedigGivenPortOnly: boolean, httpBodyEncoding, jsonPath, expectedValue, system_service_name, kafkaProducerTopic, kafkaProducerBrokers: string[], kafkaProducerSsl: boolean, kafkaProducerAllowAutoTopicCreation: boolean, kafkaProducerMessage, screenshot: string|null ("/screenshots/<jwt>.png" for real-browser), cacheBust: boolean, remote_browser, screenshot_delay, snmpOid, jsonPathOperator, snmpVersion, smtpSecurity, rabbitmqNodes: string[], conditions: Condition[], ntpStratumThreshold, ntpTimeOffsetThreshold, ntpRootDispersionThreshold, ipFamily, sftpPath, sshAuthMethod ("password" default), ping_numeric: boolean, ping_count, ping_per_request_timeout, saveResponse: boolean, saveErrorResponse: boolean, responseMaxLength (default 1024), includeSensitiveData: boolean`

Sensitive part (`includeSensitiveData=true` for socket pushes; `false` for `monitorJSON` passed to notification providers):
`headers, body, grpcBody, grpcMetadata, basic_auth_user, basic_auth_pass, oauth_client_id, oauth_client_secret, oauth_token_url, oauth_scopes, oauth_audience, oauth_auth_method, bearer_token, gamedigToken, pushToken, databaseConnectionString, radiusUsername, radiusPassword, radiusSecret, mqttUsername, mqttPassword, mqttWebsocketPath, authWorkstation, authDomain, tlsCa, tlsCert, tlsKey, kafkaProducerSaslOptions: object, rabbitmqUsername, rabbitmqPassword, sshUsername, sshPassword, sshPrivateKey, sshPassphrase`

Not exposed in `MonitorJSON` although stored: `snmp_v3_username`, `manual_status`, `expected_tls_alert`, `user_id`, `created_date`, `dns_last_result` is exposed.

### 5.4 `CertInfo` (payload of `certInfo` after `JSON.parse`) - `server/util-server.js:457-527`

`{ valid: boolean, certInfo: { subject: {CN,...}, issuer: {...}, subjectaltname, valid_from, valid_to, fingerprint, fingerprint256, serialNumber, validTo: ISO string, validFor: string[], daysRemaining: number, certType: "server"|"intermediate CA"|"root CA"|"self-signed", issuerCertificate: CertInfo|null, raw: {type:"Buffer",data:[...]} ... }, hostnameMatchMonitorUrl: boolean }`
(`certInfo` is Node's `getPeerCertificate(true)` object augmented; chain is nested via `issuerCertificate`.)

### 5.5 `UptimeDataResult` (`server/uptime-calculator.js:563-695`)
`{ uptime: number /*0..1*/, avgPing: number|null }` - used for `uptime`/`avgPing` events and badges.

See `maintenance-and-status-pages.md` for `MaintenanceJSON`, `StatusPageJSON`, `IncidentPublic`, and `notification-providers.md` for notification objects.
