# Uptime Kuma 2.5.8 payload fixtures

Recorded 2026-10-28T22:39:57.587Z from a local `louislam/uptime-kuma:2.5.8` (see `infra/docker-compose.dev.yml`)
by `infra/kuma-seed/record.mjs --duration 120 --twofa --per-key 3` after `setup.mjs` and `seed.mjs`.
Kuma version string from the `info` event: `2.5.8`. Server timezone: `Europe/Berlin` (+01:00).

Secrets are redacted: JWTs become `REDACTED_JWT`, values under keys matching
`token|password|secret|api_key|accesstoken` become `REDACTED(len=N)`, otpauth URIs lose their secret.

## Layout

- `<event>.json` - server->client Socket.IO events. `samples[].args` are the positional emit arguments.
  At most 3 samples per bucket (`sampleKeys`), e.g. one bucket per heartbeat status / monitor id / uptime period.
- `ack/<event>[.<variant>].json` - client->server emits with the callback (`response`) the server returned.
- `rest/<name>.json` - plain HTTP responses from the public REST endpoints (and `/metrics` with basic auth).

## Server->client events

| file | samples | total received | buckets |
|---|---:|---:|---|
| `apiKeyList.json` | 3 | 4 | all |
| `avgPing.json` | 42 | 148 | monitor=1, monitor=2, monitor=3, monitor=4, monitor=5, monitor=6, monitor=7, monitor=8, ... |
| `certInfo.json` | 18 | 58 | monitor=1, monitor=2, monitor=8, monitor=10, monitor=12, monitor=18 |
| `deleteMonitorFromList.json` | 2 | 2 | all |
| `dockerHostList.json` | 3 | 3 | all |
| `domainInfo.json` | 27 | 91 | monitor=1, monitor=2, monitor=4, monitor=6, monitor=8, monitor=10, monitor=12, monitor=13, ... |
| `heartbeat.json` | 43 | 109 | status=1:monitor=3, status=1:monitor=4, status=3:monitor=10, status=0:monitor=9, status=1:monitor=6, status=1:monitor=11, status=1:monitor=1, status=0:monitor=7, ... |
| `heartbeatList.json` | 13 | 13 | monitor=1, monitor=2, monitor=3, monitor=4, monitor=5, monitor=6, monitor=7, monitor=8, ... |
| `info.json` | 2 | 2 | hidden-version, full |
| `loginRequired.json` | 1 | 1 | all |
| `maintenanceList.json` | 3 | 5 | all |
| `monitorList.json` | 3 | 4 | all |
| `monitorTypeList.json` | 3 | 3 | all |
| `notificationList.json` | 3 | 3 | all |
| `proxyList.json` | 3 | 3 | all |
| `remoteBrowserList.json` | 3 | 3 | all |
| `statusPageList.json` | 3 | 3 | all |
| `updateMonitorIntoList.json` | 3 | 6 | monitor=18 |
| `uptime.json` | 9 | 444 | period=24, period=720, period=1y |

## Ack (callback) fixtures

| file | event | ok |
|---|---|---|
| `ack/needSetup.json` | `needSetup` | - |
| `ack/login.wrong-password.json` | `login` | false |
| `ack/login.unknown-user.json` | `login` | false |
| `ack/login.ok.json` | `login` | true |
| `ack/loginByToken.ok.json` | `loginByToken` | true |
| `ack/logout.json` | `logout` | - |
| `ack/loginByToken.invalid.json` | `loginByToken` | false |
| `ack/getMonitorList.json` | `getMonitorList` | true |
| `ack/getMonitor.http.json` | `getMonitor` | true |
| `ack/getMonitorBeats.24h.json` | `getMonitorBeats` | true |
| `ack/getMonitorBeats.1h.json` | `getMonitorBeats` | true |
| `ack/getMonitorBeats.invalid-period.json` | `getMonitorBeats` | false |
| `ack/getMonitorChartData.24h.json` | `getMonitorChartData` | true |
| `ack/getMonitorChartData.720h.json` | `getMonitorChartData` | true |
| `ack/getMonitorChartData.1y.json` | `getMonitorChartData` | true |
| `ack/monitorImportantHeartbeatListCount.one.json` | `monitorImportantHeartbeatListCount` | true |
| `ack/monitorImportantHeartbeatListPaged.one.json` | `monitorImportantHeartbeatListPaged` | true |
| `ack/monitorImportantHeartbeatListCount.all.json` | `monitorImportantHeartbeatListCount` | true |
| `ack/monitorImportantHeartbeatListPaged.all.json` | `monitorImportantHeartbeatListPaged` | true |
| `ack/getMonitor.group.json` | `getMonitor` | true |
| `ack/getMonitor.push.json` | `getMonitor` | true |
| `ack/getMonitor.failing.json` | `getMonitor` | true |
| `ack/getMonitorBeats.failing-24h.json` | `getMonitorBeats` | true |
| `ack/getMonitor.tagged.json` | `getMonitor` | true |
| `ack/getMonitor.not-found.json` | `getMonitor` | false |
| `ack/getSettings.json` | `getSettings` | true |
| `ack/getTags.json` | `getTags` | true |
| `ack/twoFAStatus.json` | `twoFAStatus` | true |
| `ack/getAPIKeyList.json` | `getAPIKeyList` | true |
| `ack/getPushExample.curl.json` | `getPushExample` | false |
| `ack/checkApprise.json` | `checkApprise` | - |
| `ack/getMaintenanceList.json` | `getMaintenanceList` | true |
| `ack/getMaintenance.json` | `getMaintenance` | true |
| `ack/getMonitorMaintenance.json` | `getMonitorMaintenance` | true |
| `ack/getMaintenanceStatusPage.json` | `getMaintenanceStatusPage` | true |
| `ack/getStatusPage.ok.json` | `getStatusPage` | true |
| `ack/getStatusPage.not-found.json` | `getStatusPage` | false |
| `ack/getIncidentHistory.json` | `getIncidentHistory` | true |
| `ack/add.validation-error.interval.json` | `add` | false |
| `ack/add.validation-error.statuscodes.json` | `add` | false |
| `ack/add.validation-error.pm2-name.json` | `add` | false |
| `ack/add.ok.json` | `add` | true |
| `ack/editMonitor.ok.json` | `editMonitor` | true |
| `ack/editMonitor.validation-error.json` | `editMonitor` | false |
| `ack/pauseMonitor.ok.json` | `pauseMonitor` | true |
| `ack/resumeMonitor.ok.json` | `resumeMonitor` | true |
| `ack/addMonitorTag.ok.json` | `addMonitorTag` | true |
| `ack/deleteMonitorTag.ok.json` | `deleteMonitorTag` | true |
| `ack/clearEvents.ok.json` | `clearEvents` | true |
| `ack/deleteMonitor.ok.json` | `deleteMonitor` | true |
| `ack/deleteMonitor.not-found.json` | `deleteMonitor` | true |
| `ack/pauseMonitor.not-found.json` | `pauseMonitor` | false |
| `ack/prepare2FA.ok.json` | `prepare2FA` | true |
| `ack/verifyToken.ok.json` | `verifyToken` | true |
| `ack/verifyToken.invalid.json` | `verifyToken` | false |
| `ack/save2FA.ok.json` | `save2FA` | true |
| `ack/twoFAStatus.enabled.json` | `twoFAStatus` | true |
| `ack/login.2fa-token-required.json` | `login` | - |
| `ack/login.2fa-invalid-token.json` | `login` | false |
| `ack/login.2fa-ok.json` | `login` | true |
| `ack/disable2FA.ok.json` | `disable2FA` | true |
| `ack/twoFAStatus.disabled.json` | `twoFAStatus` | true |

## REST fixtures

| file | status |
|---|---:|
| `rest/entry-page.json` | 200 |
| `rest/status-page.json` | 200 |
| `rest/status-page.not-found.json` | 404 |
| `rest/status-page.heartbeat.json` | 200 |
| `rest/status-page.manifest.json` | 200 |
| `rest/status-page.incident-history.json` | 200 |
| `rest/metrics.basic-auth.json` | 200 |
| `rest/metrics.unauthorized.json` | 401 |
| `rest/badge.status.json` | 200 |
| `rest/badge.uptime-24h.json` | 200 |
| `rest/push.up.json` | 200 |
| `rest/push.down.json` | 200 |
| `rest/push.invalid-token.json` | 404 |

## 2FA

recorded (prepare2FA, verifyToken, save2FA, login tokenRequired / invalid / ok, disable2FA).

## Seeded monitors at recording time

| id | type | name | parent | tags |
|---:|---|---|---:|---|
| 1 | http | HTTP example.com |  |  |
| 2 | keyword | HTTP keyword example.com |  |  |
| 3 | json-query | JSON query Kuma entry-page |  |  |
| 4 | port | TCP example.com:443 |  |  |
| 5 | ping | Ping 1.1.1.1 |  |  |
| 6 | dns | DNS example.com A |  |  |
| 7 | push | Push demo |  |  |
| 8 | http | Tagged HTTP example.org |  | env:dev, team:android |
| 9 | http | Failing HTTP localhost:9 |  |  |
| 10 | http | Under maintenance HTTP |  |  |
| 11 | group | Group Web |  |  |
| 12 | http | Group child HTTP example.com | 11 |  |
| 13 | port | Group child TCP example.com:80 | 11 |  |
