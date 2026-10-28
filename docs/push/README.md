# Push delivery: Uptime Kuma -> self-hosted ntfy -> UnifiedPush -> app

Spike date: 2026-10-28. Sources read at these versions (all primary):

| Component | Version | Source |
|---|---|---|
| Uptime Kuma | 2.5.8 (latest 2.5.x tag) | `server/notification-providers/{ntfy,webhook,notification-provider}.js`, `server/model/{monitor,heartbeat}.js`, `server/notification.js`, `server/server.js`, `src/components/notifications/{Ntfy,Webhook}.vue` |
| ntfy server | v2.34.0 (latest release; Docker image `binwiederhier/ntfy:v2.34.0`) | `server/server.go`, `server/config.go`, `cmd/serve.go`, `docs/config.md`, `docs/install.md` |
| ntfy Android app (distributor) | v1.25.2 | `app/src/main/java/io/heckel/ntfy/up/{BroadcastReceiver,Distributor}.kt`, `msg/NotificationDispatcher.kt`, `util/Util.kt`, `service/*Connection.kt`, `app/src/play/.../FirebaseService.kt` |
| UnifiedPush android-connector | **3.3.5** (Maven Central `org.unifiedpush.android:connector`, released 2026-08-22; the GitHub mirror only has tag 3.0.10, the sources jar from Maven Central was read) | `UnifiedPush.kt`, `PushService.kt`, `data/{PushMessage,PushEndpoint,ResolvedDistributor}.kt`, `FailedReason.kt`, AAR `AndroidManifest.xml` |

Everything in the "Test log" section was executed against a real ntfy v2.34.0 container (port 2587, `auth-default-access: deny-all`).

---

## 1. Decision: **Variant B** (Kuma `webhook` -> UnifiedPush endpoint directly)

Kuma notification type `webhook`, `webhookContentType: "custom"`, POSTing a small JSON body straight to the endpoint URL the distributor hands out (`https://ntfy.example/upXXXXXXXXXXXX?up=1`), with `Authorization: Bearer <token>` as an additional header.

No "A fallback" is needed. If the webhook provider ever became unusable, the equivalent fallback is the `ntfy` provider with `ntfyUseTemplate: true` and the same JSON template as `ntfyCustomMessage` (see 1.2), not the stock `ntfy` provider.

### 1.1 Why B and not A

1. **The ntfy Android distributor hands the app only the raw bytes of ntfy's `message` field.** `NotificationDispatcher.kt`:
   ```kotlin
   if (distribute) {
       safeLet(subscription.upAppId, subscription.upConnectorToken) { appId, connectorToken ->
           distributor.sendMessage(appId, connectorToken, decodeBytesMessage(notification))
       }
   }
   ```
   `decodeBytesMessage()` (`util/Util.kt:210`) returns `notification.message.toByteArray()` (or base64-decodes it when ntfy flagged the body as binary). **Title, priority, tags, actions, click, icon are all dropped.** With variant A (stock Kuma `ntfy` provider) the app would receive only `heartbeatJSON.msg` as text, e.g. `timeout` or `200 - OK`, with no monitor id, no status, no name (those are only in `title`/`tags`, which never arrive). Unusable for app logic.
2. **ntfy stores a `?up=1` body verbatim.** `server.go` `handlePublishBody` case 2 -> `handleBodyAsMessageAutoDetect`: `m.Message = string(body.PeekedBytes) // Do not trim`. Valid UTF-8 is stored as-is, otherwise base64 with `encoding: "base64"`. So whatever JSON Kuma renders is exactly what `onMessage` gets (verified, test B1/B5 below).
3. **It is what the UnifiedPush spec intends**: the endpoint is an opaque URL the application server POSTs the payload to. Kuma's webhook provider is exactly that application server. No topic/server-URL splitting, no `ntfy`-specific JSON envelope.
4. **Kuma already supports everything needed**: `webhookContentType: "custom"` + `webhookCustomBody` (LiquidJS template, context: `msg`, `monitorJSON`, `heartbeatJSON`, `name`, `status`, `hostnameOrURL`) + `webhookAdditionalHeaders` (JSON string merged into axios headers). `webhook.js` and `notification-provider.js` in 2.5.8, quoted in section 3.
5. **UP messages are explicitly excluded from upstream/FCM paths** (`dispatchOpts{ ..., upstream: !unifiedpush }` and `firebase = false` when `up=1`), so the self-hosted server never leaks even a poll request to ntfy.sh for these topics.

### 1.2 Why not "A with a custom ntfy message template"

It would work (the template would end up in `message`), but adds nothing: the ntfy provider still sends title/tags/actions that the distributor discards, it needs `ntfyserverurl` + `ntfytopic` parsed out of the endpoint URL, and messages go through `handleBodyAsTextMessage` (trimmed) instead of the raw UP path. Keep it as the documented fallback only.

---

## 2. Exact Kuma notification object (socket.io `addNotification`)

Kuma call: `socket.emit("addNotification", notification, notificationID, callback)` (`server/server.js:1567`). `notificationID` is `null` to create, or the existing id to update (e.g. when the endpoint changes). `Notification.save()` stores `name`, `is_default` and `JSON.stringify(notification)` as `config`; `applyExisting` is consumed once and not persisted. The callback returns `{ ok, msg, id }`.

```json
{
  "name": "Pixel 8 (UnifiedPush)",
  "type": "webhook",
  "isDefault": false,
  "applyExisting": true,
  "webhookURL": "https://ntfy.example.com/upAbCdEfGhIjKl?up=1",
  "httpMethod": "post",
  "webhookContentType": "custom",
  "webhookCustomBody": "<template below, single line>",
  "webhookAdditionalHeaders": "{\"Authorization\":\"Bearer tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx\",\"Content-Type\":\"application/json\"}"
}
```

Field notes (all from `webhook.js` / `Webhook.vue` 2.5.8):

| Field | Value | Why |
|---|---|---|
| `type` | `"webhook"` | provider key in `server/notification.js` providerList |
| `name` | free text | shown in Kuma UI; include the device name so the user can identify/revoke it |
| `isDefault` | `false` (or `true` if the app wants every *new* monitor to use it) | `bean.is_default` |
| `applyExisting` | `true` to attach to all existing monitors of the user in one go, else `false` | one-shot, calls `applyNotificationEveryMonitor` |
| `webhookURL` | the `PushEndpoint.url` **verbatim**, including `?up=1` | `axios.post(notification.webhookURL, data, config)`; the Vue form validates `https?://.+` only client-side |
| `httpMethod` | `"post"` | `get` would put everything in query params |
| `webhookContentType` | `"custom"` | selects `renderTemplate(webhookCustomBody, msg, monitorJSON, heartbeatJSON)` |
| `webhookCustomBody` | template below | LiquidJS 10.26, `strictVariables` off |
| `webhookAdditionalHeaders` | **a JSON string** (not an object) | `JSON.parse(notification.webhookAdditionalHeaders)`; invalid JSON throws "Additional Headers is not a valid JSON" |

### 2.1 Template (`webhookCustomBody`), 888 bytes, verified with LiquidJS 10.26

```liquid
{"v":1,"monitor":{"id":{{ monitorJSON.id | json | default: "null" }},"name":{{ monitorJSON.name | json | default: "null" }},"type":{{ monitorJSON.type | json | default: "null" }},"url":{{ monitorJSON.url | json | default: "null" }},"hostname":{{ monitorJSON.hostname | json | default: "null" }},"port":{{ monitorJSON.port | json | default: "null" }}},"heartbeat":{"status":{{ heartbeatJSON.status | json | default: "null" }},"msg":{{ heartbeatJSON.msg | json | default: "null" }},"time":{{ heartbeatJSON.time | json | default: "null" }},"localDateTime":{{ heartbeatJSON.localDateTime | json | default: "null" }},"ping":{{ heartbeatJSON.ping | json | default: "null" }},"duration":{{ heartbeatJSON.duration | json | default: "null" }},"retries":{{ heartbeatJSON.retries | json | default: "null" }},"important":{{ heartbeatJSON.important | json | default: "null" }}},"msg":{{ msg | json }}}
```

Why every value goes through `| json | default: "null"`:
- `json` emits a correctly quoted/escaped JSON literal for strings, numbers, booleans and `null` (handles `"` and unicode in monitor names and error messages).
- LiquidJS renders an **undefined** variable piped through `json` as an *empty string*, which produces invalid JSON (`"lastDownTime":}`). This happens for the Kuma **test notification** (`monitorJSON` and `heartbeatJSON` are `null`, `msg` is `"<name> Testing"`) and for optional heartbeat fields. `default: "null"` turns that into the literal `null`. `0`, `false` and `""` survive because `json` turns them into non-empty strings first.

Rendered outputs (exact, from the spike):

```
DOWN (384 bytes): {"v":1,"monitor":{"id":12,"name":"Web \"prod\" ✓","type":"http","url":"https://example.com/health","hostname":null,"port":null},"heartbeat":{"status":0,"msg":"timeout of 48000ms exceeded","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":null,"duration":60,"retries":2,"important":true},"msg":"[Web \"prod\" ✓] [🔴 Down] timeout of 48000ms exceeded"}
UP   (342 bytes): {"v":1,"monitor":{"id":12,"name":"Web \"prod\" ✓","type":"http","url":"https://example.com/health","hostname":null,"port":null},"heartbeat":{"status":1,"msg":"200 - OK","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":123,"duration":60,"retries":0,"important":true},"msg":"[Web \"prod\" ✓] [✅ Up] 200 - OK"}
PING (331 bytes): {"v":1,"monitor":{"id":7,"name":"Router","type":"ping","url":"https://","hostname":"10.0.0.1","port":null},"heartbeat":{"status":0,"msg":"Request timed out","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":null,"duration":60,"retries":2,"important":true},"msg":"[Router] [🔴 Down] Request timed out"}
TEST (249 bytes): {"v":1,"monitor":{"id":null,"name":null,"type":null,"url":null,"hostname":null,"port":null},"heartbeat":{"status":null,"msg":null,"time":null,"localDateTime":null,"ping":null,"duration":null,"retries":null,"important":null},"msg":"My Phone Testing"}
```

Payload contract for the app (`v: 1`):
- `monitor.id` (int|null), `monitor.name`, `monitor.type` (`http`, `ping`, `port`, `push`, `dns`, ...), `monitor.url` (Kuma stores `"https://"` as the empty default for non-HTTP types), `monitor.hostname`, `monitor.port`.
- `heartbeat.status`: `0` DOWN, `1` UP, `2` PENDING, `3` MAINTENANCE (`server/model/heartbeat.js`). Kuma only *notifies* on `isImportantForNotification` transitions: first beat, UP->DOWN, DOWN->UP, PENDING->DOWN, MAINTENANCE->DOWN (`monitor.js:1425`), plus `resendInterval` repeats while DOWN. So in practice `status` is 0 or 1.
- `heartbeat.msg` (raw check message, Kuma substitutes `"N/A"` if empty), `heartbeat.time` (UTC `YYYY-MM-DD HH:mm:ss.SSS`), `heartbeat.localDateTime` (server timezone, `YYYY-MM-DD HH:mm:ss`), `heartbeat.ping` (ms|null), `heartbeat.duration` (seconds since previous beat), `heartbeat.retries`, `heartbeat.important` (always `true` for notifications).
- `msg`: Kuma's default text `[<name>] [🔴 Down|✅ Up] <bean.msg>` (`monitor.js:1468`); for a test notification `"<notification name> Testing"`.
- If `monitor.id` is `null` -> treat as test notification.

### 2.2 Headers (`webhookAdditionalHeaders`)

```json
{"Authorization":"Bearer tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx","Content-Type":"application/json"}
```

- `Authorization: Bearer <token>`: ntfy accepts Bearer tokens and Basic auth identically (`docs/publish.md` "Authentication"; both verified, tests B1 and "Basic auth" below). Use a token for the `kuma` user, never the password.
- `Content-Type`: **not required by ntfy.** The publish handler only inspects `Content-Type` to detect markdown (`isMarkdownContentType`); `?up=1` bypasses all body heuristics. Kuma's axios (`~0.32.0`) sends `application/x-www-form-urlencoded` for string bodies when nothing is set; verified harmless (test "form-urlencoded"). Set `application/json` anyway so proxies/logs are honest.
- `X-Title` / `X-Priority` / `X-Tags`: ntfy **does** parse and store them even with `?up=1` (`parsePublishParams` runs before the body switch; verified test B4). But the ntfy Android app discards them for UP subscriptions (section 1.1), and `webhookAdditionalHeaders` is a static JSON string (not templated), so they cannot carry per-event priority anyway. **Omit them**; they only matter if someone reads the topic in the ntfy web UI.

### 2.3 Size budget

ntfy `message-size-limit` default is 4096 bytes (`server/config.go:74`, `DefaultMessageSizeLimit = 4096`). For `?up=1` bodies ntfy **does not reject** larger bodies: `util.Peek(r.Body, MessageSizeLimit)` reads only the first 4096 bytes and `handleBodyAsMessageAutoDetect` stores exactly those -> **HTTP 200 and silent truncation** (test E5: 4971-byte body -> 200, `len(message)=4096`). Without `?up=1` the same body is treated as an attachment and rejected with `400 attachments not allowed` (test E6).

Measured sizes for a realistic HTTP monitor with 2 tags (Python reconstruction of `monitor.toJSON(preloadData, false)` for 2.5.8):

| Payload | Bytes |
|---|---|
| `monitorJSON` alone | 2263 |
| `heartbeatJSON` (no `response`) | 281 |
| Kuma default `webhookContentType: "json"` body (`{heartbeat, monitor, msg}`) | 2633 |
| same with a 1024-char saved response body (`responseMaxLength` default 1024, `saveErrorResponse` on) | 3655 |
| **our custom template, DOWN** | **384** |

So the stock JSON webhook body would fit *today* but only with ~400 bytes headroom, grows with every Kuma release, and would overflow as soon as a user raises `responseMaxLength`. The custom template leaves >3.5 KB headroom; the only unbounded inputs are `monitor.name` and `heartbeat.msg`. The app must still parse defensively (section 5.3).

---

## 3. What arrives in `onMessage`, and how to parse it

Chain: Kuma `axios.post(endpoint, renderedString)` -> ntfy stores `message = <renderedString>` (raw, untrimmed) -> ntfy Android app's `SubscriberService` (JSON-stream or WebSocket connection, `since=<lastNotificationId>`) receives it -> `NotificationDispatcher` -> `Distributor.sendMessage(appId, token, decodeBytesMessage(notification))` -> UnifiedPush connector `MessagingReceiverImpl` -> our `PushService.onMessage(PushMessage, instance)`.

- `PushMessage.content: ByteArray` = exactly the UTF-8 bytes of the rendered template. `PushMessage.decrypted` is `false` (no WebPush encryption is used; Kuma cannot encrypt. The connector still generates a `PublicKeySet` on registration; ignore `endpoint.pubKeySet`).
- Parse: `String(message.content, Charsets.UTF_8)` -> JSON. `v == 1`, `monitor.id == null` -> test notification; else map `heartbeat.status`.
- Ordering/duplicates: ntfy delivers in order per topic; after a reconnect the ntfy app replays from `since=<lastNotificationId>` (ntfy `cache-duration`, default 12h, set via `NTFY_CACHE_DURATION`; needs `cache-file` to survive restarts). Use `monitor.id + heartbeat.time` as the dedupe key.
- The connector acquires a wake lock and the distributor raises the app to the foreground for delivery (`RaiseToForegroundService`), so `onMessage` runs in the background even in Doze; keep the work short (post a notification, enqueue a sync).

---

## 4. ntfy server setup

Production compose block: [`ntfy-compose-snippet.yml`](./ntfy-compose-snippet.yml). Minimum configuration (env form; every key is also a `server.yml` key):

| Env | Value | Why |
|---|---|---|
| `NTFY_BASE_URL` | `https://ntfy.example.com` | required for correct endpoint URLs and the web app; the ntfy app's UP endpoint is `"${baseUrl}/${topic}?up=1"` built from the *user's* default server, so it must match what users enter |
| `NTFY_BEHIND_PROXY` | `true` | Traefik in front: without it all visitors share one IP for rate limiting (`docs/config.md` "Rate limiting"); header read is `X-Forwarded-For` by default |
| `NTFY_AUTH_FILE` | `/var/lib/ntfy/user.db` | enables auth |
| `NTFY_AUTH_DEFAULT_ACCESS` | `deny-all` | private server; anonymous gets nothing (test E1 -> 403) |
| `NTFY_CACHE_FILE` | `/var/lib/ntfy/cache.db` | message cache survives restarts; needed for `since=` replay after the phone was offline |
| `NTFY_CACHE_DURATION` | `24h` (default 12h) | how long an offline phone can catch up |
| `NTFY_ATTACHMENT_CACHE_DIR` | unset | attachments disabled; irrelevant for UP and shrinks the attack surface |
| `NTFY_MESSAGE_SIZE_LIMIT` | leave default `4K` | our payload is ~400 B. Raising it logs a warning ("not recommended and largely untested", `cmd/serve.go:423`) and breaks FCM/APNS paths; hard cap 5M |
| `NTFY_VISITOR_SUBSCRIBER_RATE_LIMITING` | leave `false` (default) | if enabled, publishing to an `up*` topic returns **HTTP 507** until the phone has subscribed at least once and the subscribing user has *read-write* on the topic (`server.go:1024`, docs "Subscriber-based rate limiting"). Our ACL gives the app user read-only, so it must stay off |
| `NTFY_UPSTREAM_BASE_URL` | **unset** | see below |

**`upstream-base-url` is not needed for this design.** It forwards a `poll_request` for every published message to ntfy.sh so that *iOS* (and FCM-based) clients get woken up (`docs/config.md` "iOS instant notifications"). Two independent reasons it does nothing here: (a) ntfy never forwards UnifiedPush messages upstream (`server.go:1087`, `upstream: !unifiedpush // UP messages are not sent to upstream`); (b) the Play-Store ntfy app only uses Firebase for topics on `app_base_url` = ntfy.sh (`FirebaseService.kt:78`, `// Everything from Firebase comes from main service URL!`); for self-hosted servers and for all UP subscriptions (`instant = true, // No Firebase, always instant!` in `up/BroadcastReceiver.kt`) it uses its own foreground-service connection. Leave it commented out in the compose file.

### 4.1 Users, ACL, tokens (run inside the container; commands edit `auth-file` directly)

```sh
# publisher identity used by Uptime Kuma (one per Kuma instance is enough)
NTFY_PASSWORD='<strong-random>' ntfy user add kuma
ntfy access kuma 'up*' write-only

# subscriber identity entered into the ntfy Android app (one per person/household, or per device)
NTFY_PASSWORD='<strong-random>' ntfy user add app
ntfy access app 'up*' read-only

# token for Kuma; put it in webhookAdditionalHeaders. Label it after the device.
ntfy token add --label 'kuma-pixel8' kuma          # prints tk_...
# optional expiry: ntfy token add --expires=365d --label ... kuma

ntfy access          # review
ntfy token list      # review
ntfy token remove kuma tk_...   # revoke (publishing then returns 401, verified)
```

Wildcard semantics: `up*` matches every topic beginning with `up`; the distributor generates `"up" + 12 random chars` (`UP_PREFIX`, `TOPIC_RANDOM_ID_LENGTH = 12` in `up/BroadcastReceiver.kt`), i.e. 14-char topics, which is also what ntfy treats as a UP topic for subscriber rate limiting (`unifiedPushTopicLength = 14`). Verified ACL behaviour: kuma can write `up*` (200), cannot read (403), cannot write `other` (403); app can read `up*` (200), cannot write (403); anonymous gets 403.

Alternative to one shared `app` user: give each user their own ntfy account with `read-only` on `up*`. Topic secrecy, not the ACL, is what separates devices (section 7).

### 4.2 Traefik notes

- WebSocket (`/topic/ws`) and the long-lived JSON stream (`/topic/json`) pass through Traefik without extra config; ntfy sends `keepalive` events every 45 s so idle timeouts do not trigger.
- Healthcheck: the image is Alpine-based with **BusyBox wget** (`/usr/bin/wget`, BusyBox v1.37.0), no curl. `wget -q --tries=1 -O- http://localhost:80/v1/health` works (BusyBox accepts and ignores `--tries`; an unknown option such as `--bogus-option` fails with exit 1, so this was verified explicitly). Output: `{"healthy":true,"role":"active"}`. ntfy's own `docs/install.md` uses the same command piped through `grep -Eo '"healthy"\s*:\s*true'` and sets `init: true` to reap zombie processes from the healthcheck.

---

## 5. UnifiedPush connector integration (app side)

**Version: `org.unifiedpush.android:connector:3.3.5`** (Maven Central, 2026-08-22; depends on `kotlin-stdlib 2.2.10` and `com.google.crypto.tink:tink 1.23.0`, `minSdk 16`). Sibling artifacts: `connector-ui:1.1.0` (optional pre-built chooser dialog), `embedded-fcm-distributor:3.1.0` (FCM fallback, **not used**: requires Google services and a Firebase project; `getDistributors()` already filters an embedded FCM distributor out when Play Services are missing). `foss-embedded-fcm-distributor` is `1.0.1-deprecated`.

```kotlin
// build.gradle.kts
implementation("org.unifiedpush.android:connector:3.3.5")
```

### 5.1 Manifest

The AAR already declares `MessagingReceiverImpl` (exported receiver for `MESSAGE`, `UNREGISTERED`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`, `TEMP_UNAVAILABLE`), the `RaiseToForegroundService`, the translucent `LinkActivity`, `WAKE_LOCK`, and the `<queries>` for `org.unifiedpush.android.distributor.{LINK,REGISTER,UNREGISTER}`. The app only adds its service:

```xml
<service android:name=".push.KumaPushService" android:exported="false">
    <intent-filter>
        <action android:name="org.unifiedpush.android.connector.PUSH_EVENT"/>
    </intent-filter>
</service>
```

### 5.2 Service

```kotlin
class KumaPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // endpoint.url == "https://ntfy.example.com/upXXXXXXXXXXXX?up=1" (ignore endpoint.pubKeySet)
        // 1. persist url; 2. if it differs from the stored Kuma notification's webhookURL
        //    -> emit addNotification(notificationWithNewUrl, existingNotificationId); if none exists -> create (id = null)
    }
    override fun onMessage(message: PushMessage, instance: String) {
        val json = String(message.content, Charsets.UTF_8)   // exactly the bytes of ntfy's `message`
        // parse per section 2.1; on parse failure show a generic "Uptime Kuma alert" and enqueue a sync
    }
    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // INTERNAL_ERROR -> retry; NETWORK -> retry when online; ACTION_REQUIRED -> tell user to open ntfy
        // (ntfy sends this when "Enable UnifiedPush" is off in its settings); VAPID_REQUIRED -> n/a (we pass no VAPID)
    }
    override fun onUnregistered(instance: String) {
        // distributor dropped us (user removed the subscription in ntfy, ntfy uninstalled, UP disabled):
        // delete the Kuma notification (deleteNotification(id)), clear stored endpoint, show "push disabled" state
    }
    override fun onTempUnavailable(instance: String) {
        // 3.3.x: distributor backend temporarily unreachable; fall back to socket.io polling; onNewEndpoint follows when back
    }
}
```

### 5.3 Register flow (on app start and from the "Enable push" screen)

```kotlin
// every app start: re-attach to the saved distributor or the system default
UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
    if (ok) UnifiedPush.register(activity)          // instance = INSTANCE_DEFAULT ("default"), no VAPID
}

// onboarding screen: decide what to show *before* the OS dialog pops
when (val r = UnifiedPush.resolveDefaultDistributor(context)) {
    is ResolvedDistributor.Found     -> { UnifiedPush.saveDistributor(context, r.packageName); UnifiedPush.register(context) }
    ResolvedDistributor.ToSelect     -> { /* explain, then */ UnifiedPush.tryUseDefaultDistributor(activity) { ok -> if (ok) UnifiedPush.register(activity) } }
    ResolvedDistributor.NoneAvailable -> { /* "Install ntfy" screen, see section 6 */ }
}

// manual chooser (settings): list = UnifiedPush.getDistributors(context)  // package names, e.g. "io.heckel.ntfy"
UnifiedPush.saveDistributor(context, chosen); UnifiedPush.register(context)
// or let the OS "open with" picker choose a *default* distributor: UnifiedPush.tryPickDistributor(activity) { ok -> ... }

// disable push: UnifiedPush.unregister(context) -> distributor removes the topic subscription; also delete the Kuma notification
```

- `register()` is idempotent per instance: the distributor answers an existing registration with the same endpoint (`BroadcastReceiver.register`, "Subscription with connectorToken ... exists. Sending endpoint"). Call it on every start; compare the URL in `onNewEndpoint` with what is stored in Kuma and update only on change.
- `saveDistributor()` to a *different* package broadcasts `UNREGISTER` to the old one (connector 3.3.5 `saveDistributor` -> `broadcastUnregister`), so `onUnregistered` will fire for the old registration; handle it by updating, not deleting, the Kuma notification if a new endpoint arrives right after.
- "No distributor installed" = `ResolvedDistributor.NoneAvailable` (equivalently `getDistributors(context).isEmpty()`).
- Human-readable distributor names: `packageManager.getApplicationLabel(getApplicationInfo(pkg, 0))`.

---

## 6. User onboarding flow

1. **Install the ntfy app** (`io.heckel.ntfy`; F-Droid, Play Store or GitHub release; all three act as UP distributors). The app shows this step when `resolveDefaultDistributor()` returns `NoneAvailable`, with deep links to `https://f-droid.org/packages/io.heckel.ntfy/`, `market://details?id=io.heckel.ntfy`, `https://github.com/binwiederhier/ntfy-android/releases`, and `https://unifiedpush.org/users/distributors/` for alternatives.
2. **In ntfy: add the server and log in as the `app` user.** Settings -> General -> *Default server* -> `https://ntfy.example.com`. Settings -> *Manage users* -> add user for that server (`app` / password). The UP subscription is created on `repository.getDefaultBaseUrl()` and the connection uses `repository.getUser(baseUrl)` (`SubscriberService.kt:280`), so both must point at the same base URL. Settings -> Advanced -> *Enable UnifiedPush* must be on (default on; if off, registration fails with `ACTION_REQUIRED`).
3. **Optional but recommended for latency**: accept ntfy's battery-optimization banner ("Fix now"), or set *Connection protocol* to WebSocket (Settings -> Advanced; default is the JSON HTTP stream, `Repository.CONNECTION_PROTOCOL_JSONHTTP`).
4. **Our app: "Enable push notifications"** -> section 5.3 flow -> ntfy may show its own one-time "ntfy is now the default UnifiedPush app" dialog -> `onNewEndpoint(url)`.
5. **Our app creates the Kuma notification** via `addNotification` (section 2) with the endpoint and the Kuma-side ntfy token (the token is entered once in our app's push settings; the admin created it with `ntfy token add`). Offer `applyExisting = true` ("attach to all my monitors") and `isDefault`.
6. **Test**: our app emits `testNotification(notification)` (Kuma sends `"<name> Testing"`, `monitorJSON = null`); the TEST payload from 2.1 arrives in `onMessage` within ~1 s if everything is wired. Show success/failure in the UI.
7. In ntfy the subscription shows up as `upXXXXXXXXXXXX (UnifiedPush)` "managed by <our app>"; ntfy never shows notifications for it itself (`shouldNotify` returns false for `upAppId != null`).

---

## 7. Delivery latency and Doze

- With ntfy as distributor, delivery latency is that of ntfy's **foreground service** holding a JSON-stream or WebSocket connection to the self-hosted server. UP subscriptions are always `instant = true`, so the service runs as soon as one exists. ntfy's docs: instant delivery works "even when your phone is in doze mode" because of the foreground service; the connection is re-established with `since=<last id>` so nothing published while offline is lost (within `cache-duration`).
- Expect sub-second to a few seconds when connected; after Doze/network loss the reconnect can add up to a few minutes. The ntfy app asks to be exempted from battery optimization for this reason; recommend accepting (step 3 above). WebSocket mode reconnects faster on flaky networks in practice.
- FCM-grade "instant from a dead-asleep phone" is **not** available without Google services, by design of this project. `upstream-base-url` would not change that for Android (section 4), so there is no hidden ntfy.sh dependency.
- Our `onMessage` runs under a connector wake lock with the app raised to the foreground; post the notification synchronously, do heavier sync via WorkManager.
- Kuma-side: the webhook is awaited in `sendNotification` with Kuma's default axios timeout (no explicit timeout in `webhook.js`); errors are logged (`Cannot send notification to <name>`) and do not affect monitoring. Kuma retries nothing; the ntfy cache is the buffer.

---

## 8. Security considerations

- **Topic secrecy is the authorization boundary between devices.** Both `kuma` (write) and `app` (read) users see every `up*` topic; the 12-random-char topic (62^12 ~ 3e21) is the per-device secret. The endpoint URL therefore must be treated as a secret: stored in Kuma's `notification.config` (readable by any Kuma user of that account) and in our app's private storage; never logged.
- **Token scoping**: the Kuma token belongs to a user with `write-only` on `up*` only. Compromise of the token lets an attacker *publish* fake alerts to guessed topics but not read anything (test E3: read -> 403) nor publish elsewhere (test E4 -> 403). The `app` user's credentials let one read all `up*` topics on that server if the topic names are known, so do not reuse the `app` user across untrusted households; create one ntfy user per trust domain.
- **Token rotation**: `ntfy token add --expires=...`, then update `webhookAdditionalHeaders` in every Kuma notification that used the old one (our app can do this via `addNotification(..., existingId)`), then `ntfy token remove`. Revocation is immediate (verified: 401 on the next publish).
- **Revoking a device** = delete its Kuma notification (`deleteNotification(id)`), `UnifiedPush.unregister()` in the app (ntfy drops the topic subscription), and, if the token was per device, `ntfy token remove kuma tk_...`. The server keeps already-cached messages of that topic until `cache-duration` expires.
- Kuma stores the Bearer token in the clear inside `notification.config`; anyone with Kuma access can read it. Per-device tokens with labels make audits and revocation possible (`ntfy token list` shows last-access IP and time).
- Kuma's payload reaches ntfy over TLS (Traefik) and is cached unencrypted on the ntfy server; it contains monitor names, URLs/hostnames and check messages. Do not add response bodies or headers to the template.
- `?up=1` bodies are never forwarded upstream and never go to Firebase (`firebase = false; unifiedpush = true` in `parsePublishParams`).
- `visitor-request-limit-burst` 60 / replenish 5 s per IP (defaults) apply to Kuma's single IP behind Traefik; a mass outage of >60 monitors at once will see 429s on the tail (Kuma logs them, no retry). Raise `NTFY_VISITOR_REQUEST_LIMIT_BURST` or add Kuma's IP to `NTFY_VISITOR_REQUEST_LIMIT_EXEMPT_HOSTS` if that matters.

---

## 9. Test log (ntfy v2.34.0, `docker run ... -p 2587:80 -e NTFY_AUTH_DEFAULT_ACCESS=deny-all -e NTFY_BEHIND_PROXY=true -e NTFY_BASE_URL=http://localhost:2587 binwiederhier/ntfy:v2.34.0 serve`)

Setup inside the container (tokens below are throwaway; the container and its data were destroyed after the spike):

```
$ NTFY_PASSWORD=kumapass ntfy user add kuma && NTFY_PASSWORD=apppass ntfy user add app
user kuma added with role user
user app added with role user
$ ntfy access kuma "up*" write-only && ntfy access app "up*" read-only
granted write-only access to topic up*
granted read-only access to topic up*
$ ntfy token add --label kuma-cli kuma   -> tk_1dhqpum98dnir06c5lrhwwe44f3qz
$ ntfy token add --label app-cli app     -> tk_ni2t30gncaaw9236qis602uhstx31
$ ntfy access
user app (role: user, tier: none)
- read-only access to topic up*
user kuma (role: user, tier: none)
- write-only access to topic up*
user * (role: anonymous, tier: none)
- no topic-specific permissions
- no access to any (other) topics (server config)
```

**B1 — variant B publish (`?up=1`, Bearer kuma token, JSON body)**
```
$ curl -s -w '\nHTTP %{http_code}\n' -X POST 'http://localhost:2587/upTESTABC?up=1' -H 'Authorization: Bearer tk_1dhq...' -H 'Content-Type: application/json' -d '{"id":12,"name":"Web","status":0,"msg":"timeout","time":"2026-10-28 20:00:00"}'
{"id":"iDsTxBTFvTIC","time":1793225990,"expires":1793269190,"event":"message","topic":"upTESTABC","message":"{\"id\":12,\"name\":\"Web\",\"status\":0,\"msg\":\"timeout\",\"time\":\"2026-10-28 20:00:00\"}"}
HTTP 200
```
**B2 — read back as `app` (Basic auth)**: identical JSON, `HTTP 200`. `message` = the body byte-for-byte; **no** `title`, `priority`, `tags` keys present. (B3: same with `Authorization: Bearer tk_ni2t...` -> 200.)

**B4 — `?up=1` plus `X-Title: Web Down`, `X-Priority: 5`, `X-Tags: red_circle,kuma`**
```
{"id":"qTZDnt9dGyp6","time":1793225990,"expires":1793269190,"event":"message","topic":"upHDRTEST01","title":"Web Down","message":"{\"id\":12,\"status\":0}","priority":5,"tags":["red_circle","kuma"]}
HTTP 200
```
Headers are honoured and stored with `?up=1` (but the ntfy Android app does not pass them on to the UP app).

**B5 — `X-UnifiedPush: 1` header instead of `?up=1`, body `"  {\"a\":1}\n\n"`**
```
{"id":"I2GD8BVMpnl3",...,"topic":"upWSTEST0001","message":"  {\"a\":1}\n\n"}
```
Raw, untrimmed. **B6 — same body without UP flag**: `"message":"{\"a\":1}"` (trimmed). Confirms `handleBodyAsMessageAutoDetect` vs `handleBodyAsTextMessage`.

**A1 — variant A (Kuma `ntfy.js` style: JSON to server root)**
```
$ curl -s -X POST 'http://localhost:2587/' -H 'Authorization: Bearer tk_1dhq...' -H 'Content-Type: application/json' -d '{"topic":"upTESTABC","message":"timeout","priority":5,"title":"Web Down [Uptime-Kuma]","tags":["red_circle"],"actions":[{"action":"view","label":"Open Web","url":"https://example.com"}]}'
{"id":"MQBlmSsBOXu9","time":1793225990,"expires":1793269190,"event":"message","topic":"upTESTABC","title":"Web Down [Uptime-Kuma]","message":"timeout","priority":5,"tags":["red_circle"],"actions":[{"id":"2GUdfqDF87","action":"view","label":"Open Web","clear":false,"url":"https://example.com"}]}
HTTP 200
```
Read back identical. With variant A the UP app would receive only the bytes `timeout`.

**E1 — publish without token**: `{"code":40301,"http":403,"error":"forbidden","link":"https://ntfy.sh/docs/publish/#authentication"}` `HTTP 403`
**E2 — publish with read-only `app` token**: same `40301` / `HTTP 403`
**E3 — read with write-only `kuma` token**: same `40301` / `HTTP 403`
**E4 — `kuma` token publishing to topic `other`**: same `40301` / `HTTP 403`

**E5 — 4971-byte JSON body with `?up=1`**
```
HTTP 200   (response shows the message cut mid-way)
read back: len(message)= 4096 encoding= None attachment= None tail= 'AAAAAAAAAAAAAAAAAAAA'
```
Silent truncation to `message-size-limit`. **E6 — same body without `?up=1`**: `{"code":40014,"http":400,"error":"invalid request: attachments not allowed","link":"https://ntfy.sh/docs/config/#attachments"}` `HTTP 400`.

**Content-Type `application/x-www-form-urlencoded` with `?up=1`** (axios default for string bodies): `"message":"{\"id\":12,\"status\":0}"` `HTTP 200`. **Basic auth `kuma:kumapass` publish**: `HTTP 200`.

**Subscribe as the ntfy app does** (`GET /upCTTEST0001/json?since=all`, Bearer app token):
```
{"id":"xazj36UwcysW","time":1793226285,"event":"open","topic":"upCTTEST0001"}
{"id":"rzQutbUK90pp","time":1793226285,"expires":1793269485,"event":"message","topic":"upCTTEST0001","message":"{\"id\":12,\"status\":0}"}
{"id":"98FNAvOhcK7P","time":1793226285,"expires":1793269485,"event":"message","topic":"upCTTEST0001","message":"{\"id\":12,\"status\":1}"}
```

**Token revocation**: `ntfy token remove kuma tk_1dhq...` -> `token ... for user kuma removed`; next publish with it: `{"code":40101,"http":401,"error":"unauthorized",...}` `HTTP 401`.

**Health**: `GET /v1/health` -> `{"healthy":true,"role":"active"}`; inside the container `wget -q --tries=1 -O- http://localhost:80/v1/health` -> same output, exit 0; `wget --bogus-option` -> `wget: unrecognized option` exit 1 (so `--tries` is genuinely accepted).

**Not tested** (no device in the spike): the Android-side hop ntfy app -> connector. It is documented from source (`NotificationDispatcher.kt`, `Distributor.kt`, connector 3.3.5 `MessagingReceiverImpl`/`PushService`) rather than observed.
