# Uptime Kuma 2.5.8 - Maintenance windows and status pages

Sources: `server/model/maintenance.js`, `server/socket-handlers/maintenance-socket-handler.js`, `src/pages/EditMaintenance.vue`,
`server/model/status_page.js`, `server/socket-handlers/status-page-socket-handler.js`, `src/pages/StatusPage.vue`, `server/routers/status-page-router.js`.

---

## 1. Maintenance

### 1.1 `MaintenanceJSON` (`Maintenance.toJSON` = `toPublicJSON`, `server/model/maintenance.js:15-103`)

Used by the `maintenanceList` push (`{ [id]: MaintenanceJSON }`), `getMaintenance` ack and the public `maintenanceList` of a status page.

```json
{
  "id": 3,
  "title": "DB upgrade",
  "description": "",
  "strategy": "recurring-weekday",
  "intervalDay": 1,
  "active": true,
  "dateRange": ["2026-11-01 00:00:00", "2026-12-31 23:59:00"],
  "timeRange": [ {"hours": 2, "minutes": 0}, {"hours": 3, "minutes": 0} ],
  "weekdays": [1, 3, 5],
  "daysOfMonth": [],
  "timeslotList": [ { "startDate": "2026-11-03T01:00:00.000Z", "endDate": "2026-11-03T02:00:00.000Z" } ],
  "cron": "0 2 * * 1,3,5",
  "duration": 3600,
  "durationMinutes": 60,
  "timezone": "Europe/Berlin",
  "timezoneOption": "SAME_AS_SERVER",
  "timezoneOffset": "+01:00",
  "status": "scheduled"
}
```

| Field | Type | Meaning / source |
|---|---|---|
| `strategy` | `"manual"` \| `"single"` \| `"cron"` \| `"recurring-interval"` \| `"recurring-weekday"` \| `"recurring-day-of-month"` | `src/pages/EditMaintenance.vue:110-124`; DB default `single` |
| `dateRange` | `[start, end]` strings; `[null]` if no start | `start_date` / `end_date` as stored (UI sends `"YYYY-MM-DDTHH:mm"` local wall-clock, interpreted in `timezone`) `:16-25` |
| `timeRange` | `[{hours, minutes}, {hours, minutes}]` | from `start_time`/`end_time` `"HH:mm"` (`parseTimeObject`, `src/util.ts:561-583`) |
| `weekdays` | `number[]` 0 (Sun) .. 6 (Sat); Monday=1 | JSON column |
| `daysOfMonth` | `(number \| "lastDay1")[]` | only `lastDay1` is supported by the generated cron (`L`); `lastDay2-4` are dropped (`:486-495`) |
| `timeslotList` | `[{startDate, endDate}]` ISO strings | manual: empty; single: `[{start_date, end_date}]`; cron/recurring: current running slot (if any) + next run `:54-83` |
| `cron` | string | user-entered for `cron`; generated for `recurring-*` (`generateCron`, `:455-503`); `""` for single/manual |
| `duration` / `durationMinutes` | seconds / `parseInt(duration/60)` | cron: `durationMinutes*60`; recurring: `end_time - start_time` (+24h if across midnight, `:131-138`) |
| `timezone` | IANA tz actually used (`SAME_AS_SERVER` resolved to server tz) | `:402-407` |
| `timezoneOption` | raw stored value: `"SAME_AS_SERVER"` \| `"UTC"` \| IANA name | `:49` |
| `timezoneOffset` | `"+HH:mm"` | `:413-415` |
| `status` | `"inactive"` \| `"under-maintenance"` \| `"scheduled"` \| `"ended"` \| `"unknown"` | `getStatus`, `:421-449`: `!active` -> inactive; manual -> under-maintenance; before `start_date` -> scheduled; after `end_date` -> ended; single -> under-maintenance; else in-memory job status (`scheduled` / `under-maintenance`) or `unknown` |

A monitor is under maintenance when any linked maintenance (or any of a parent group's) has status `under-maintenance` (`server/model/monitor.js:1601-1623`). `MonitorJSON.maintenance` reflects that; heartbeats then get status 3 and msg `"Monitor under maintenance"` (`:475-477`).

### 1.2 Payload for `addMaintenance` / `editMaintenance` (`Maintenance.jsonToBean`, `server/model/maintenance.js:146-195`)

```json
{
  "id": 3,                           // editMaintenance only
  "title": "string (required, max 150)",
  "description": "string",
  "strategy": "single",
  "active": 1,                       // UI sends 1 / true
  "intervalDay": 1,                  // recurring-interval: every N days (>= 1)
  "dateRange": ["2026-10-28T10:00", "2026-10-28T11:00"],   // [start, end]; either may be null/"" (end optional for recurring; both required for single)
  "timeRange": [ {"hours": 2, "minutes": 0}, {"hours": 3, "minutes": 0} ],  // recurring-*: start/end wall-clock
  "weekdays": [1, 2, 3, 4, 5, 6, 0],  // recurring-weekday
  "daysOfMonth": [1, 15, "lastDay1"], // recurring-day-of-month
  "cron": "30 3 * * *",               // strategy cron (also default in UI)
  "durationMinutes": 60,              // strategy cron
  "timezoneOption": "SAME_AS_SERVER"  // or "UTC" or IANA name
}
```

Server rules:
- `dateRange[0]` / `[1]` must parse as dates with year <= 9999, else `"Invalid start date"` / `"Invalid end date"` (`:158-178`).
- `cron` strategy: `duration = durationMinutes*60`, cron validated with `croner` (`:180-184`).
- `recurring-*`: `start_time`/`end_time` from `timeRange` via `parseTimeFromTimeObject` (`"HH:mm"`), `weekdays`/`daysOfMonth` JSON-encoded, cron generated and validated (`:186-193`).
  - `recurring-interval`: cron `"<m> <h>  * * *"`, run only if `intervalDay` days passed since `last_start_date` (`:268-309`).
  - `recurring-weekday`: `"<m> <h> * * <sorted weekdays>"`.
  - `recurring-day-of-month`: `"<m> <h> <days[,L]> * *"`.
- `single`: a cron job fires at `start_date` in the maintenance tz; status derives from dates (`:233-238`, `:431-442`).
- `manual`: active -> under maintenance until paused (`:426-428`).
- The handler sets `user_id = socket.userID`, stores, runs `bean.run(true)` (throws on bad cron -> `{ok:false}`), then pushes `maintenanceList`.
- UI defaults for a new maintenance (`src/pages/EditMaintenance.vue:251-273`): `strategy:"single"`, `active:1`, `cron:"30 3 * * *"`, `durationMinutes:60`, `intervalDay:1`, `dateRange:[now, now+1h]` (`"YYYY-MM-DDTHH:mm"` local), `timeRange:[{2,0},{3,0}]`, `weekdays:[]`, `daysOfMonth:[]`, `timezoneOption:"SAME_AS_SERVER"`.
- After add/edit the UI calls `addMonitorMaintenance(maintenanceID, [{id}, ...])` and `addMaintenanceStatusPage(maintenanceID, [{id}, ...])` which **replace** the respective link tables (`maintenance-socket-handler.js:77-138`). At least one monitor or status page must be selected (UI rule only, `EditMaintenance.vue:380-384`).

### 1.3 Socket events (all auth) - see `socket-events.md` 3.9

`addMaintenance`, `editMaintenance`, `getMaintenance`, `getMaintenanceList`, `deleteMaintenance`, `pauseMaintenance` (sets `active=false`, stops job), `resumeMaintenance` (sets `active=true`, re-runs), `addMonitorMaintenance`, `addMaintenanceStatusPage`, `getMonitorMaintenance` -> `{monitors:[{id}]}`, `getMaintenanceStatusPage` -> `{statusPages:[{id,title}]}`.

`maintenanceList` is re-pushed to the owner on: any of the above, every *important* heartbeat (`server/model/monitor.js:990`), window start/end of cron/recurring jobs (`maintenance.js:254-260`), and `setSettings`.

### 1.4 Public maintenance on status pages

`StatusPageData.maintenanceList` = `toPublicJSON()` of every maintenance linked to the page that is currently `under-maintenance` (`server/model/status_page.js:563-587`). Same shape as `MaintenanceJSON`.

---

## 2. Status pages

### 2.1 Admin `StatusPageJSON` (`StatusPage.toJSON`, `server/model/status_page.js:438-460`) - `statusPageList`, `getStatusPage.config`

```json
{
  "id": 1, "slug": "default", "title": "My Status", "description": "markdown or null",
  "icon": "/icon.svg", "theme": "auto", "autoRefreshInterval": 300, "published": true, "showTags": false,
  "domainNameList": ["status.example.com"], "customCSS": "", "footerText": null, "showPoweredBy": true,
  "analyticsId": null, "analyticsScriptUrl": null, "analyticsType": null,
  "showCertificateExpiry": false, "showOnlyLastHeartbeat": false, "rssTitle": null
}
```

Public variant (`toPublicJSON`, `:467-487`) drops `id` and `domainNameList`.

### 2.2 `addStatusPage(title, slug, cb)` (`status-page-socket-handler.js:436-479`)
- Both trimmed and required; slug lower-cased; `checkSlug`: `^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$` (`:533-547`).
- Creates row with `theme:"auto"`, `icon:""`, `autoRefreshInterval:300`. UNIQUE slug violation surfaces as `{ok:false, msg:"... UNIQUE constraint ..."}` (UI special-cases it, `src/pages/AddStatusPage.vue:96-100`).
- ack `{ok:true, msg:"successAdded", msgi18n:true, slug}`.

### 2.3 `saveStatusPage(slug, config, imgDataUrl, publicGroupList, cb)` (`:292-433`)

- `slug`: the **current** slug (lookup key). `config.slug` may rename it (validated with `checkSlug`; the UI trims and lower-cases it, `StatusPage.vue:1152`).
- `config` fields written (`:324-346`): `slug, title, description, autoRefreshInterval, theme ("auto"|"light"|"dark"), showTags, footerText, customCSS, showPoweredBy, rssTitle, showOnlyLastHeartbeat, showCertificateExpiry, analyticsId, analyticsScriptUrl, analyticsType (null | "google"|"umami"|"plausible"|"matomo"|"rybbit" else error "Invalid analytics type"), domainNameList (string[])`. `published`, `search_engine_index`, `password` are not editable.
- `imgDataUrl`: either `"data:image/png;base64,..."` (saved to `data/upload/logo<id>.png`, `config.logo` becomes `/upload/logo<id>.png?t=<ms>`; non-PNG data URLs -> `"Only allowed PNG logo."`) or any other string (treated as an external URL / existing path). Send the current `config.icon` to keep the logo.
- `domainNameList` replaces `status_page_cname` rows; a domain used by another page is moved (`server/model/status_page.js:384-416`).
- `publicGroupList`: array of groups, order = weight:
  ```json
  [ { "id": 7, "name": "Services", "monitorList": [ { "id": 5, "sendUrl": true, "url": "https://custom.example" } ] } ]
  ```
  - Group with `id` must already belong to this page and be public, else a new group row is created (`:359-366`); groups not in the list are deleted (`:400-409`).
  - For each monitor: `monitor_group` rows are recreated with `weight` = position; `sendUrl` (optional, boolean) -> `send_url`; `url` (optional) -> `custom_url` (`:377-394`). The UI pushes the full `MonitorJSON` of the selected monitor into `monitorList` (`StatusPage.vue:926-936`), the server only reads `id`, `sendUrl`, `url`.
- If the page is the entry page and the slug changed, setting `entryPage` is updated. Cache cleared.
- ack `{ok:true, publicGroupList}` (same array with `id`s filled in). The UI then navigates to `/status/<newSlug>`.

### 2.4 `deleteStatusPage(slug, cb)` (`:482-523`) - deletes incidents, groups, page; resets `entryPage` to `dashboard` if needed. ack `{ok:true}`.

### 2.5 Incidents (`:34-266`)

`IncidentPublic` = `{id, style, title, content, pin: boolean, active: boolean, createdDate, lastUpdatedDate, status_page_id}` (`server/model/incident.js:21-33`). Dates are `R.isoDateTime(dayjs.utc())` -> `"YYYY-MM-DD HH:mm:ss"` UTC.

| Event | Payload | Behaviour |
|---|---|---|
| `postIncident(slug, {id?, title, content, style}, cb)` | `style`: `info`\|`warning`\|`danger`\|`primary`\|`light`\|`dark` (UI default `primary`, `StatusPage.vue:1289-1293`) | creates (or updates when `id` matches an incident of that page) with `pin=true, active=true`; sets `created_date` (new) or `last_updated_date` (edit). ack `{ok:true, incident}`. **No empty-title validation here** (only `editIncident` validates). |
| `editIncident(slug, incidentID, {title, content, style, pin?}, cb)` | validates non-empty title/content (`"Please input title"`, `"Please input content"`); invalid style -> `warning`; `pin` defaults to true | ack `{ok:true, msg:"Saved.", msgi18n:true, incident}` |
| `unpinIncident(slug, cb)` | - | `UPDATE incident SET pin=0 WHERE pin=1 AND status_page_id=?`; ack `{ok:true}` |
| `resolveIncident(slug, incidentID, cb)` | - | `active=false, pin=false, last_updated_date=now`; ack `{ok:true, msg:"Resolved", msgi18n:true, incident}` |
| `deleteIncident(slug, incidentID, cb)` | - | hard delete; ack `{ok:true, msg:"successDeleted", msgi18n:true}` |
| `getIncidentHistory(slug, cursor, cb)` | public | `{ok:true, incidents, total, nextCursor, hasMore}`; 10 per page; admin (logged in) and public currently return the same `toPublicJSON` |

Only incidents with `pin=1 AND active=1` appear in `StatusPageData.incidents` (`server/model/status_page.js:318-323`).

### 2.6 Public REST shapes

Documented in `rest-endpoints.md` section 4: `GET /api/status-page/:slug` (`{config, incidents, publicGroupList, maintenanceList}`), `GET /api/status-page/heartbeat/:slug` (`{heartbeatList:{[monitorId]: [{status,time,msg:"",ping}]}, uptimeList:{"<id>_24": fraction}}`), `GET /api/status-page/:slug/manifest.json`, `GET /api/status-page/:slug/incident-history`, `GET /api/status-page/:slug/badge`, `GET /status/:slug/rss`.

Public monitor entry (`Monitor.toPublicJSON`, `server/model/monitor.js:84-107`): `{id, name, sendUrl (0|1), type, url? (custom_url ?? url, only when sendUrl), tags? [{id, monitor_id, tag_id, value, name, color}], certExpiryDaysRemaining?, validCert?}`.
The web status page shows a link only when `sendUrl && url && url !== "https://"` (`src/components/PublicGroupList.vue:253-269`).

Overall status for RSS/badge (`server/model/status_page.js:218-241`): any MAINTENANCE -> maintenance; else all UP -> all up; some UP + some not -> partial; no UP -> all down. `STATUS_PAGE_ALL_DOWN=0, ALL_UP=1, PARTIAL_DOWN=2, MAINTENANCE=3` (`src/util.ts:37-40`).
