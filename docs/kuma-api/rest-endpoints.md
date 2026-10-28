# Uptime Kuma 2.5.8 - HTTP / REST endpoints

Source: tag `2.5.8`. Routes are registered in `server/server.js:264-386`, `server/routers/api-router.js`, `server/routers/status-page-router.js`.
Express `~4.22.1`, `app.use(express.json())` is global (`server/server.js:213`).

Error helper `sendHttpError(res, msg)` (`server/util-server.js:717-735`): JSON `{status:"fail", msg}` with HTTP **503** for SQLite busy/locked,
**404** when `msg` contains "not found" (case-insensitive), otherwise **403**.

CORS: `allowAllOrigin` (`Access-Control-Allow-Origin: *`, `server/util-server.js:632-636`) is applied only to the badge routes;
`allowDevAllOrigin` on the status-page JSON routes is a no-op outside `NODE_ENV=development` (`:621-625`). Native clients are unaffected.

Caching: `apicache` middleware (`cache("5 minutes")` etc.) caches responses in memory; `apicache.clear()` is called after status-page edits,
monitor deletes, maintenance and API-key changes, and after every *important* heartbeat (`server/model/monitor.js:988`).

Global headers: `X-Frame-Options: SAMEORIGIN` (unless disabled), `X-Powered-By` removed (`server/server.js:216-222`).

---

## 1. Entry / misc

| Method & path | Auth | Response | Source |
|---|---|---|---|
| `GET /` | none | Redirect: if `Host` (or `X-Forwarded-Host` when `trustProxy`) is a mapped status-page domain -> renders that status page; else if setting `entryPage` starts with `statusPage-` -> `302 /status/<slug>`; else `302 /dashboard` | `server/server.js:264-286` |
| `GET /api/entry-page` | none | `{type:"statusPageMatchedDomain", statusPageSlug}` or `{type:"entryPage", entryPage:"dashboard"\|"statusPage-<slug>"}` | `server/routers/api-router.js:27-44` |
| `GET /setup-database-info` | none | `{runningSetup:false, needSetup:false}` (always, once the main server runs) | `server/server.js:288-294` |
| `GET /robots.txt` | none | `User-agent: *\nDisallow:` plus ` /` unless setting `searchEngineIndex` | `:342-349` |
| `GET /.well-known/change-password` | none | 302 to the wiki page "Reset-Password-via-CLI" | `:367-369` |
| `GET /upload/*` | none | static files from `data/upload` (status page logos `logo<id>.png`) | `:365` |
| `GET /screenshots/<jwt>.png` | none (capability URL) | real-browser screenshots; file name is `jwt.sign(monitorId, jwtSecret)` from `MonitorJSON.screenshot` | `server/uptime-kuma-server.js:208`, `server/model/monitor.js:119-121` |
| `GET /*` | none | SPA `index.html` (anything under `/upload/` that does not exist -> 404 "File not found.") | `server/server.js:380-386` |
| dev only: `POST /test-webhook`, `POST /test-x-www-form-urlencoded`, `GET /_e2e/*` | none | only when `NODE_ENV=development` | `:296-339` |

---

## 2. Push monitor endpoint

### `ALL /api/push/:pushToken` - `server/routers/api-router.js:46-145`

Any HTTP method. Query parameters:

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | `"up"` \| anything else | `"up"` | anything other than exactly `"up"` is treated as DOWN (`:51-52`) |
| `msg` | string | `"OK"` | stored as heartbeat `msg` |
| `ping` | float (ms) | `null` | must be 0..100000000000 else error (`:54-59`) |

Behaviour:
- Monitor looked up by `push_token = ? AND active = 1`; otherwise error `"Monitor not found or not active."`.
- Under maintenance -> status `MAINTENANCE` (3). Otherwise `determineStatus` (`:563-606`) applies `upsideDown` and the retry/PENDING logic using `maxretries`.
- Writes a heartbeat (`duration` = seconds since previous beat), computes `important`, sends notifications (incl. `resendInterval` logic), emits socket `heartbeat` to the owner's room and `sendStats`.
- Success: HTTP 200 `{ ok: true }`. Any error: HTTP **404** `{ ok: false, msg: string }`.
- Push token: 32 chars `[A-Za-z0-9]` generated client-side (`genSecret(32)`, `src/pages/EditMonitor.vue:3292,3819`; `src/util.ts:536-544`); DB column `push_token VARCHAR(32)`.
- Example URL built by the UI: `<primaryBaseURL or origin>/api/push/<token>?status=up&msg=OK&ping=` (`src/pages/EditMonitor.vue:3512-3514`).

---

## 3. Badges (SVG) - `server/routers/api-router.js:147-552`

All badge routes: `GET`, no auth, `cache("5 minutes")`, CORS `*`, `Content-Type: image/svg+xml`, built with `badge-maker`.
A monitor is "public" if it belongs to at least one **public group** of a status page (`isMonitorPublic`, `:613-624`); otherwise the badge shows `N/A` in grey (`#999`).
Common query params: `style` (badge-maker style, default `flat`), `label`, `labelPrefix`, `labelSuffix`, `prefix`, `suffix`, `color`, `labelColor`, `value` (demo override).
Colours (`src/util.ts:144-162`): up `#66c20a`, warn `#eed202`, down `#c2290a`, pending `#f8a306`, maintenance `#1747f5`, ping `blue`.

| Path | Params | Output |
|---|---|---|
| `/api/badge/:id/status` | `upLabel="Up"`, `downLabel="Down"`, `pendingLabel="Pending"`, `maintenanceLabel="Maintenance"`, `upColor`, `downColor`, `pendingColor`, `maintenanceColor`, `label` (default "Status") | last heartbeat status (`:147-218`) |
| `/api/badge/:id/uptime/:duration?` | `duration` like `24h`, `30d`, `1y`, or bare number = hours (default `24h`); `labelSuffix="h"`, `suffix="%"` | `(uptime*100).toPrecision(4)`; colour from `percentageToColor` unless `color` given; label `Uptime (24h)` (`:220-282`). Duration units: `m,h,d,w,M,y` (`server/uptime-calculator.js:775-798`) |
| `/api/badge/:id/ping/:duration?` | as above, `suffix="ms"`, `color="blue"` | avg ping over duration, label `Avg. Ping (24h)` (`:284-345`) |
| `/api/badge/:id/avg-response/:duration?` | `duration` = hours number, clamped to 720 | avg ping, label `Avg. Response (24h)`; `N/A` if no data (`:347-409`) |
| `/api/badge/:id/cert-exp` | `date` (truthy -> show `validTo` instead of days), `warnDays=14`, `downDays=7`, `upColor`, `warnColor`, `downColor`, `suffix=" days"` | `No/Bad Cert` (grey) if no TLS info; `Bad Cert` (downColor) if invalid; else days remaining coloured by thresholds (`:411-492`) |
| `/api/badge/:id/response` | `suffix="ms"`, `color="blue"` | last heartbeat ping; `N/A` if none (`:494-552`) |
| `/api/status-page/:slug/badge` | `label`, `upColor`, `downColor`, `partialColor="#F6BE00"`, `maintenanceColor="#808080"`, `style` | overall page status: `Maintenance` / `Up` / `Degraded` (some up, some down) / `Down` / `N/A` (`server/routers/status-page-router.js:170-262`) |

Errors go through `sendHttpError` (JSON), e.g. invalid id -> 403 `{status:"fail", msg:"Invalid monitor ID"}`.

---

## 4. Status pages (public) - `server/routers/status-page-router.js`

| Method & path | Cache | Response | Source |
|---|---|---|---|
| `GET /status/:slug` | 5 min | Server-side rendered SPA HTML with `<script id="preload-data">window.preloadData = {...}</script>` (same JSON as the API below), OG meta tags, manifest link; 404 + index.html if slug unknown. Slug lower-cased. | `:16-20`, `server/model/status_page.js:57-71, 160-212` |
| `GET /status` and `GET /status-page` | 5 min | status page with slug `default` | `:28-36` |
| `GET /status/:slug/rss` | 5 min | RSS 2.0 feed (`application/rss+xml`): one item per pinned incident, one per monitor currently DOWN; title = `rssTitle` or `"<title> RSS Feed"` | `:22-26`, `server/model/status_page.js:38-152` |
| `GET /api/status-page/:slug` | 5 min | `StatusPageData` (below); 404 `{status:"fail", msg:"Status Page Not Found"}` | `:39-60` |
| `GET /api/status-page/heartbeat/:slug` | 1 min | `{ heartbeatList: { [monitorId]: HeartbeatPublic[] /*oldest first, max 100*/ }, uptimeList: { "<monitorId>_24": number /*0..1*/ } }` | `:64-110` |
| `GET /api/status-page/:slug/manifest.json` | 1440 min | `{name, start_url:"/status/<slug>", display:"standalone", icons:[{src, sizes:"128x128", type:"image/png"}]}` | `:113-143` |
| `GET /api/status-page/:slug/incident-history?cursor=<created_date>` | 5 min | `{ok:true, incidents: IncidentPublic[], total, nextCursor, hasMore}` (page size 10) | `:145-167`, `server/model/status_page.js:517-556` |

### `StatusPageData` (`server/model/status_page.js:314-345`)

```json
{
  "config": {
    "slug": "default", "title": "...", "description": "markdown|null", "icon": "/icon.svg | /upload/logo1.png?t=... | https://...",
    "autoRefreshInterval": 300, "theme": "auto|light|dark", "published": true, "showTags": false,
    "customCSS": "", "footerText": null, "showPoweredBy": true,
    "analyticsId": null, "analyticsScriptUrl": null, "analyticsType": null,
    "showCertificateExpiry": false, "showOnlyLastHeartbeat": false, "rssTitle": null
  },
  "incidents": [ IncidentPublic ],
  "publicGroupList": [
    { "id": 1, "name": "Services", "weight": 1,
      "monitorList": [ { "id": 5, "name": "Site", "sendUrl": 0, "type": "http",
                         "url": "https://... (only when sendUrl; custom_url wins)",
                         "tags": [ {"id","monitor_id","tag_id","value","name","color"} ] /* only when showTags */,
                         "certExpiryDaysRemaining": 42, "validCert": true /* only when showCertificateExpiry */ } ] }
  ],
  "maintenanceList": [ MaintenancePublic /* only those currently under maintenance */ ]
}
```

- `config` = `StatusPage.toPublicJSON()` (`:467-487`) - no `id`, no `domainNameList` (those are in the admin `toJSON`, `:438-460`).
- Monitor entries = `Monitor.toPublicJSON(showTags, certExpiry)` (`server/model/monitor.js:84-107`), group = `Group.toPublicJSON` (`server/model/group.js:13-27`).
- `HeartbeatPublic` = `{status, time, msg:"", ping}` (`server/model/heartbeat.js:19-26`).
- `IncidentPublic` = `{id, style, title, content, pin: boolean, active: boolean, createdDate, lastUpdatedDate, status_page_id}` (`server/model/incident.js:21-33`); `style` in `info|warning|danger|primary|light|dark`.
- `MaintenancePublic`: see `maintenance-and-status-pages.md`.

---

## 5. Prometheus metrics

### `GET /metrics` - `server/server.js:355`, auth `apiAuth` (`server/auth.js:155-176`)

- If setting `disableAuth` is true: no auth.
- Else HTTP **Basic** auth (`express-basic-auth`, `challenge: true` -> `WWW-Authenticate: Basic`):
  - if setting `apiKeysEnabled` is true (set automatically when the first API key is created, `server/socket-handlers/api-key-socket-handler.js:37`): password must be an API key `uk<id>_<secret>` (username ignored); validated via bcrypt against `api_key.key`, must be `active` and not past `expires` (`server/auth.js:41-63`). Rate limit 60/min.
  - otherwise: username/password of a Kuma user (`userAuthorizer`, rate limit 20/min on failures).
- Body: `prometheus-api-metrics` default registry (process/HTTP metrics) plus Kuma gauges (`server/prometheus.js:62-96`):
  `monitor_cert_days_remaining`, `monitor_cert_is_valid` (1/0), `monitor_uptime_ratio{window}`, `monitor_response_time_seconds{window}`, `monitor_response_time` (ms), `monitor_status` (1 UP, 0 DOWN, 2 PENDING, 3 MAINTENANCE).
  Labels: `monitor_id, monitor_name, monitor_type, monitor_url, monitor_hostname, monitor_port` plus one label per tag (sanitised tag name; new tags need a restart) (`server/prometheus.js:20-27, 41-60`).

---

## 6. Socket.IO HTTP endpoint

`GET/POST /socket.io/?EIO=4&transport=polling|websocket` - see `socket-events.md` section 1. There is no REST equivalent for monitor CRUD; everything else goes through Socket.IO.
