# Uptime Kuma 2.5.8 - Monitor types and the monitor object

Machine-readable companion: `spec/monitor-types.json` (generated from the same analysis; use it for code generation).
Sources: `src/pages/EditMonitor.vue` (form), `server/server.js:743-998` (`add` / `editMonitor`), `server/model/monitor.js:116-264` (`toJSON`),
`server/model/monitor.js:1630-1785` (`validate`), `server/uptime-kuma-server.js:113-138` (registered types), `db/knex_init_db.js` + `db/knex_migrations/*` (columns).

## 1. Type list (34 types)

Values are the `type` strings stored in the DB / sent in the monitor object. Labels are the English option texts of the `<select id="type">` (`src/pages/EditMonitor.vue:32-120`).

| `type` | Label | optgroup | Implementation | Availability |
|---|---|---|---|---|
| `http` | HTTP(s) | General | inline `server/model/monitor.js:478-732` | |
| `keyword` | HTTP(s) - Keyword | General | inline `:688-713` | |
| `port` | TCP Port | General | `server/monitor-types/tcp.js` | |
| `ping` | Ping | General | inline `:733-744` | |
| `dns` | DNS | General | `server/monitor-types/dns.js` | |
| `docker` | Docker Container | General | inline `:783-846` | |
| `system-service` | System Service | General | `server/monitor-types/system-service.js` | only if `info.runtime.platform` in `linux`,`win32` and not `info.isContainer` |
| `pm2` | PM2 Process | General | `server/monitor-types/pm2.js` | |
| `real-browser` | HTTP(s) - Browser Engine (Chrome/Chromium) (Beta) | General | `server/monitor-types/real-browser-monitor-type.js` | |
| `websocket-upgrade` | Websocket Upgrade | General (listed twice) | `server/monitor-types/websocket-upgrade.js` | |
| `sftp` | SFTP | General | `server/monitor-types/sftp.js` | |
| `group` | Group | Special | `server/monitor-types/group.js` | |
| `push` | Push | Passive | inline `:745-782`, `server/routers/api-router.js:46-145` | |
| `manual` | Manual | Passive | `server/monitor-types/manual.js` | |
| `globalping` | Globalping - Access global monitoring probes | Specific | `server/monitor-types/globalping.js` | needs setting `globalpingApiToken` for higher limits |
| `grpc-keyword` | gRPC(s) - Keyword | Specific | `server/monitor-types/grpc.js` | |
| `json-query` | HTTP(s) - Json Query | Specific | inline `:714-732` | |
| `kafka-producer` | Kafka Producer | Specific | inline `:888-905` | |
| `mqtt` | MQTT | Specific | `server/monitor-types/mqtt.js` | |
| `ntp` | NTP | Specific | `server/monitor-types/ntp.js` | |
| `rabbitmq` | RabbitMQ | Specific | `server/monitor-types/rabbitmq.js` | |
| `sip-options` | SIP Options Ping | Specific | `server/monitor-types/sip-options.js` | not in container |
| `smtp` | SMTP | Specific | `server/monitor-types/smtp.js` | |
| `snmp` | SNMP | Specific | `server/monitor-types/snmp.js` | |
| `tailscale-ping` | Tailscale Ping | Specific | `server/monitor-types/tailscale-ping.js` | not in container |
| `sqlserver` | Microsoft SQL Server | Database | `server/monitor-types/mssql.js` | |
| `mongodb` | MongoDB | Database | `server/monitor-types/mongodb.js` | |
| `mysql` | MySQL/MariaDB | Database | `server/monitor-types/mysql.js` | |
| `oracledb` | Oracle Database | Database | `server/monitor-types/oracledb.js` | |
| `postgres` | PostgreSQL | Database | `server/monitor-types/postgres.js` | |
| `radius` | Radius | Database | inline `:847-873` | |
| `redis` | Redis | Database | `server/monitor-types/redis.js` | |
| `gamedig` | GameDig | Game Server | `server/monitor-types/gamedig.js` | |
| `steam` | Steam Game Server | Game Server | `server/monitor-types/steam.js` | needs setting `steamAPIKey` |

The server-side `monitorTypeList` push (`server/client.js:214-236`) covers only the 26 class-based types; the 8 inline types never appear in it (and therefore have `supportsConditions` undefined -> false on the client).

Not present in 2.5.8 (despite older docs): `kafka-producer` is inline, `tls` monitor exists only as a DB column `expected_tls_alert` without UI/implementation, `uploadBackup` is gone.

## 2. Common fields (shown for every type)

| Field | Type | Default (new monitor) | Required | Notes / source |
|---|---|---|---|---|
| `type` | select | `"http"` | yes | `EditMonitor.vue:32-120` |
| `name` | text | `""` -> defaultFriendlyName on submit | effectively yes | hostname > system_service_name > URL host > "defaultFriendlyName" (`:3437-3457`, `:4338-4340`) |
| `parent` | group monitor id or `null` | `null` | no | `:2200-2212`; server rejects descendants (`server.js:829-835`) |
| `description` | text | `null`/`""` | no | `:2214-2224` |
| `interval` | number s | `60` (`ntp`: 300) | yes | min 1 (`MIN_INTERVAL_SECOND`, `src/util.ts:46`); UI confirms < 20 |
| `maxretries` | number | `0` | yes | `:1602-1616` |
| `retryInterval` | number s | `60` | yes (hidden unless `maxretries>0`) | min 1 (`monitor.js:1635-1637`); UI mirrors `interval` while equal |
| `resendInterval` | number | `0` | yes | re-notify every N DOWN beats (`monitor.js:994-1007`) |
| `upsideDown` | checkbox | `false` | no | `:1866-1879` |
| `notificationIDList` | `{[id]: true}` | defaults = all `isDefault` notifications | no | `:2240-2262`, `:4077-4081` |
| `tags` | `MonitorTag[]` | `[]` | no | **not sent in add/edit**; use tag events (`TagsManager.vue:318-390`) |
| `accepted_statuscodes` | `string[]` | `["200-299"]` | **server-mandatory for all types** | `server.js:751-756`, `:842-845` |
| `conditions` | array | `[]` | send always | `conditions` column NOT NULL default `[]` |
| `active` | boolean | `true` | - | only honoured by `add` (`false` = create paused) |

## 3. Type-specific fields

See `spec/monitor-types.json` -> `field_catalogue` (117 entries, each with `visible_for`, `visible_if`, `default`, `required`, `options`, `db_column`, source lines) and `types.<type>.fields` (ordered list of visible fields per type). Highlights:

### HTTP family (`http`, `keyword`, `json-query`)
- `url` (required, `https?://`), `method` (GET POST PUT PATCH DELETE HEAD OPTIONS), `httpBodyEncoding` (`json` | `form` | `xml`), `body`, `headers` (JSON object string), `maxredirects` (10), `accepted_statuscodes`, `ipFamily` (`null`|`ipv4`|`ipv6`), `timeout` (48 s, clamped to 80% of interval), `ignoreTls`, `expiryNotification`, `domainExpiryNotification`, `cacheBust`, `saveErrorResponse` (true), `saveResponse` (false), `responseMaxLength` (1024, max 1 MiB), `proxyId`.
- Authentication `authMethod`: `null` | `basic` (`basic_auth_user`, `basic_auth_pass`) | `bearer` (`bearer_token`) | `oauth2-cc` (`oauth_auth_method` `client_secret_basic`|`client_secret_post`, `oauth_token_url`, `oauth_client_id`, `oauth_client_secret`, `oauth_scopes`, `oauth_audience`) | `ntlm` (`basic_auth_user`, `basic_auth_pass`, `authDomain`, `authWorkstation`) | `mtls` (`tlsCert`, `tlsKey`, `tlsCa`). Server use: `monitor.js:482-516, 622-632, 1160-1169`.
- `keyword`: `keyword` (required), `invertKeyword`. Server: response body (stringified if not string) `includes(keyword)` (`monitor.js:688-713`).
- `json-query`: `jsonPath` (JSONata, default `$`), `jsonPathOperator` (`>`,`>=`,`<`,`<=`,`!=`,`==`,`contains`; default `==`), `expectedValue` (string), `retryOnlyOnStatusCodeFailure` (when `maxretries>0`). Server: `src/util.ts:669-740`.

### `websocket-upgrade`
`url` (`wss?://`), `wsSubprotocol`, `headers`, `accepted_statuscodes` (default `["1000"]`, close codes 1000-4999), `wsIgnoreSecWebsocketAcceptHeader`, `timeout`, `authMethod` (`null`|`basic`|`bearer`|`oauth2-cc`|`mtls`) + the same auth sub-fields, `domainExpiryNotification`.

### `real-browser`
`url`, `remote_browser` (id from `remoteBrowserList` or `null`), `screenshot_delay` (ms, < 0.5*interval), `domainExpiryNotification`. `MonitorJSON.screenshot` gives the screenshot URL.

### `port`, `ping`, `tailscale-ping`, `sip-options`
`hostname` (+ `port` for `port`/`sip-options`). `ping` adds `ping_count` (3), `ping_numeric` (true), `packetSize` (56), `ping_per_request_timeout` (2 s), `timeout` (10 s, 1..300). `domainExpiryNotification` for all four.

### `dns`
`hostname` (FQDN, not IP unless PTR; root `.` allowed), `dns_resolve_server` (default `1.1.1.1`; comma separated list), `port` (53), `dns_resolve_type` (`A AAAA CAA CNAME MX NS PTR SOA SRV TXT`), `conditions` (variable `record`, string operators). Read-only result: `dns_last_result`.

### `docker`
`docker_container` (name or id), `docker_host` (id from `dockerHostList`; required), `ignoreTls` is **not** shown but used (`monitor.js:794`).

### `push`
`pushToken` (client-generated 32 chars). Server marks DOWN when no push arrives within `interval` + 1 s buffer (`monitor.js:745-782`). Hidden "Advanced" header but `upsideDown` etc. still shown.

### `manual`
`manual_status` (`1` Up / `0` Down / `null`), set with buttons. Not returned by `toJSON`.

### `group`
Only common fields. Children set `parent`. Deleting: see `deleteMonitor(monitorID, deleteChildren)`.

### `globalping`
`subtype` (`ping`|`http`|`dns`), `location` (magic string, default `world`), `ipFamily`, `protocol` (ping: `ICMP`|`TCP`; http: `null`|`HTTP2`; dns: `UDP`|`TCP`), `hostname` (ping/dns), `url` (http), `port` (dns 53; ping+TCP 80), `dns_resolve_server` (http/dns), `dns_resolve_type` (dns; extended RR list), `keyword` (dns regex match; http keyword check), `ping_count` (ping), `method` (`HEAD`|`GET`|`OPTIONS`), `headers`, `authMethod` (`null`|`basic`|`bearer`|`oauth2-cc`), `accepted_statuscodes`, `expiryNotification`, `ignoreTls`, `cacheBust`, frontend-only `responsecheck` (`null`|`keyword`|`json-query`) selecting `keyword`/`invertKeyword` vs `jsonPath`/`jsonPathOperator`/`expectedValue`.

### `grpc-keyword`
`grpcUrl`, `keyword`, `invertKeyword`, `grpcEnableTls`, `grpcServiceName`, `grpcMethod`, `grpcProtobuf`, `grpcBody`, (`grpcMetadata` no UI), `maxredirects`, `accepted_statuscodes`, `ipFamily`, `domainExpiryNotification`.

### `kafka-producer`
`kafkaProducerBrokers` (string[]), `kafkaProducerTopic`, `kafkaProducerMessage`, `kafkaProducerSsl`, `kafkaProducerAllowAutoTopicCreation`, `kafkaProducerSaslOptions` (`{mechanism: None|plain|scram-sha-256|scram-sha-512|aws, username?, password?, authorizationIdentity?, accessKeyId?, secretAccessKey?, sessionToken?}`), `timeout` (1 s).

### `mqtt`
`hostname` (full URL `mqtt://`, `mqtts://`, `ws://`, `wss://`), `port`, `mqttUsername`, `mqttPassword`, `mqttTopic`, `mqttWebsocketPath` (only when hostname is a URL), `mqttCheckType` (`keyword`|`json-query`), `mqttSuccessMessage` (keyword), `jsonPath`+`expectedValue` (json-query), `conditions` (variables `topic`, `message` string; `json_value` string+number). Conditions take precedence over `mqttCheckType` when non-empty (`server/monitor-types/mqtt.js:37-47`).

### `ntp`
`hostname`, `port` (123), `ntpStratumThreshold` (5, 1..15), `ntpTimeOffsetThreshold` (1000 ms), `ntpRootDispersionThreshold` (500 ms); default `interval` 300.

### `rabbitmq`
`rabbitmqNodes` (string[] of management URLs, http(s) required), `rabbitmqUsername`, `rabbitmqPassword`, `timeout`.

### `smtp`
`hostname`, `port`, `smtpSecurity` (`secure`|`nostarttls`|`starttls`), `domainExpiryNotification`.

### `snmp`
`hostname`, `port` (161), `radiusPassword` (= community string, required), `snmpOid`, `snmpVersion` (`"1"`|`"2c"`|`"3"`, default `2c`), `snmpV3Username` (v3; **write-once via `add`, not copied by `editMonitor`, not in `toJSON`** - upstream gap), `jsonPath`, `jsonPathOperator`, `expectedValue`, `timeout` (5 s).

### `sftp`
`hostname`, `port` (22), `sshUsername`, `sshAuthMethod` (`password`|`privateKey`), `sshPassword`, `sshPrivateKey`, `sshPassphrase`, `sftpPath`.

### Databases
- `sqlserver`, `postgres`, `mysql`, `oracledb`: `databaseConnectionString` (templates in spec), `databaseQuery` (SQL, optional). `mysql` additionally `radiusPassword` (password override). `oracledb` additionally `basic_auth_user`/`basic_auth_pass`. `conditions` variable `result` (string) for sqlserver/mysql/oracledb (not postgres).
- `mongodb`: `databaseConnectionString`, `databaseQuery` (JSON command, validated), `jsonPath`, `expectedValue`.
- `redis`: `databaseConnectionString`, `ignoreTls`.
- `radius`: `hostname`, `port` (1812), `radiusUsername`, `radiusPassword`, `radiusSecret`, `radiusCalledStationId`, `radiusCallingStationId`.

### Game servers
- `gamedig`: `game` (from `getGameList`), `hostname`, `port` (from game default), `gamedigToken`, `gamedigGivenPortOnly` (default true; UI shows inverted "guess port").
- `steam`: `hostname`, `port`; uses setting `steamAPIKey`.

### `system-service` / `pm2`
`system_service_name` (service name or PM2 process name; `getPM2ProcessList` lists processes).

## 4. The `add` payload

`socket.emit("add", monitor, cb)` with `monitor` = `monitor_defaults_new` from the spec merged with type-specific values. Rules (`server/server.js:743-815`):

1. Never include: `id`, `path`, `pathName`, `childrenIDs`, `weight`, `forceInactive`, `maintenance`, `screenshot`, `includeSensitiveData`, `dns_last_result`, `tags`. redbean would try to write them as columns.
2. `accepted_statuscodes`: array of strings, mandatory.
3. `notificationIDList`: object; stripped from the bean and written as relations.
4. `kafkaProducerBrokers`, `kafkaProducerSaslOptions`, `conditions`, `rabbitmqNodes` are stringified by the server - send them as JSON values, not strings.
5. `headers`, `body`, `grpcBody`, `grpcMetadata`, `databaseQuery` (mongodb) are **strings** containing JSON.
6. Frontend-only keys deleted by the server: `humanReadableInterval`, `globalpingdnsresolvetypeoptions`, `responsecheck`.
7. `retryOnlyOnStatusCodeFailure` is mapped to `retry_only_on_status_code_failure`.
8. `active: false` creates a paused monitor (used for draft groups, `EditMonitor.vue:4392-4415`).
9. `validate()` errors: `Interval cannot be less than 1 seconds`, `Retry interval cannot be less than 1 seconds`, `Response max length cannot be ...`, `... must be valid JSON: ...`, `Service Name is required.` / `PM2 process name is required.`, `Invalid service name ...`, ping range errors, `Screenshot delay must be ...`, `Invalid JSON in database query: ...` (`server/model/monitor.js:1630-1785`).
10. ack `{ok:true, msg:"successAdded", msgi18n:true, monitorID}`; the server also emits `updateMonitorIntoList` and starts the monitor.
11. The UI then attaches tags (`addTag` / `addMonitorTag`) using the returned `monitorID`.

Minimal valid HTTP monitor:

```json
{ "type":"http", "name":"Example", "url":"https://example.com", "method":"GET", "interval":60, "retryInterval":60,
  "resendInterval":0, "maxretries":0, "timeout":48, "accepted_statuscodes":["200-299"], "maxredirects":10,
  "ignoreTls":false, "upsideDown":false, "expiryNotification":false, "domainExpiryNotification":true,
  "httpBodyEncoding":"json", "authMethod":null, "notificationIDList":{}, "conditions":[], "parent":null,
  "kafkaProducerBrokers":[], "kafkaProducerSaslOptions":{"mechanism":"None"}, "rabbitmqNodes":[] }
```

(`kafkaProducerBrokers`/`kafkaProducerSaslOptions`/`rabbitmqNodes` may be omitted - `JSON.stringify(undefined)` yields `undefined` and the column stays NULL, and `toJSON` then returns `null` for them; the UI always sends them.)

## 5. The `editMonitor` payload

`socket.emit("editMonitor", monitor, cb)`; `monitor.id` required. Only the fields listed in `spec/monitor-types.json -> edit_monitor_copied_fields` are applied (`server/server.js:847-967`); everything else is ignored, so echoing the full `MonitorJSON` back (as the web UI does) is safe. `notificationIDList` is replaced entirely (`:977`). `accepted_statuscodes` must again be an array of strings. ack `{ok:true, msg:"Saved.", msgi18n:true, monitorID}`.

## 6. MonitorJSON (what the server sends back)

See `socket-events.md` section 5.3 for the full list. Boolean columns are coerced to real booleans (`ignoreTls`, `upsideDown`, `invertKeyword`, `expiryNotification`, `domainExpiryNotification`, `grpcEnableTls`, `cacheBust`, `gamedigGivenPortOnly`, `kafkaProducerSsl`, `kafkaProducerAllowAutoTopicCreation`, `ping_numeric`, `saveResponse`, `saveErrorResponse`, `wsIgnoreSecWebsocketAcceptHeader`, `retryOnlyOnStatusCodeFailure`, `active`, `forceInactive`, `maintenance`). JSON columns are parsed (`accepted_statuscodes`, `kafkaProducerBrokers`, `kafkaProducerSaslOptions`, `rabbitmqNodes`, `conditions`). `proxyId` comes from `proxy_id`. `timeout` may be `0`/`null` for old monitors - the UI substitutes `ping ? 10 : floor(interval*8)/10` (`EditMonitor.vue:4136-4144`) and `retryInterval === 0` -> `interval` (`:4133-4135`).

## 7. Conditions structure

```json
[
  { "type": "expression", "variable": "record", "operator": "contains", "value": "1.2.3.4", "andOr": "and" },
  { "type": "group", "andOr": "or", "children": [
      { "type": "expression", "variable": "record", "operator": "starts_with", "value": "10.", "andOr": "and" } ] }
]
```

Operators (`server/monitor-conditions/operators.js:14-40`): string `equals, not_equals, contains, not_contains, starts_with, not_starts_with, ends_with, not_ends_with`; number `num_equals, num_not_equals, lt, gt, lte, gte`. Available variables per type come from the `monitorTypeList` event. Evaluation: left-to-right, each item's `andOr` combines it with the accumulated result (`server/monitor-conditions/evaluator.js:38-83`).
