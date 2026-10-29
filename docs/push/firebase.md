# Push delivery via Firebase Cloud Messaging (bring your own Firebase)

An optional second push transport next to [UnifiedPush via ntfy](./README.md). Alerts travel

```
Uptime Kuma --webhook--> FCM relay (your server) --FCM HTTP v1--> Google FCM --> app
```

The Kuma notification is the same `webhook` notification with the same custom JSON body (schema
v1, [README section 2.1](./README.md#21-template-webhookcustombody-888-bytes-verified-with-liquidjs-1026));
only the URL and the bearer token differ. The app parses the FCM message exactly like a
UnifiedPush payload.

## Relay

### What it does

`relay/` is a small, stateless Go service (one static binary, distroless image of about 10 MB).
For every request

```
POST https://<relay-host>/v1/fcm/<FCM registration token>
Authorization: Bearer <RELAY_TOKEN>
Content-Type: application/json

{"v":1,"monitor":{...},"heartbeat":{...},"msg":"..."}
```

it

1. applies a per-client rate limit (token bucket, default 60/min, burst 30) so a misconfigured Kuma
   cannot burn your FCM quota,
2. checks the bearer token in constant time (`401` otherwise),
3. enforces the body limit (`MAX_BODY_BYTES`, default 4096, FCM's data payload limit; `413`),
4. sanity-checks the registration token (100 to 4096 characters of `[A-Za-z0-9_:-]`; `400`),
5. forwards the raw body, unchanged, as a high-priority FCM **data** message:

   ```json
   {"message":{"token":"<registration token>",
               "data":{"v":"1","body":"<raw Kuma body as a string>"},
               "android":{"priority":"HIGH","ttl":"3600s"}}}
   ```

   `data.v` is the envelope version, `data.body` is the exact string Kuma rendered. There is no
   `notification` block: the app decides what to show, also when it is in the background.

The relay stores nothing. It signs a JWT with the service account key (RS256, stdlib crypto, no
Google client libraries), exchanges it for an OAuth access token (cached until shortly before it
expires) and calls `https://fcm.googleapis.com/v1/projects/<project>/messages:send` with a 10 s
timeout per attempt. It never logs the registration token or the body; log lines carry a 12-hex
fingerprint (`token=6cd307498ed0`, first 6 bytes of the token's SHA-256) to correlate requests of
one device.

#### Responses (what Kuma sees)

| Status | Meaning | What to do |
|---|---|---|
| `200` | Accepted by FCM, body `{"status":"sent","name":"projects/.../messages/..."}` | nothing |
| `400` | Malformed registration token in the URL, empty or non-UTF-8 body, or FCM rejected the message itself (`INVALID_ARGUMENT` not about the token, e.g. too large) | fix the notification |
| `401` | Missing or wrong `Authorization: Bearer` | fix the header in Kuma |
| `403` | `SENDER_ID_MISMATCH`: the token was issued for a different Firebase project than the relay's service account | the app and the relay must use the same Firebase project |
| `410` | FCM says the token is gone (`UNREGISTERED`, or `INVALID_ARGUMENT` for `message.token`): the app was uninstalled, its data cleared, or it registered a new token | delete or update this notification in Kuma |
| `413` | Body larger than `MAX_BODY_BYTES` | shorten the template |
| `429` | Relay rate limit hit (`Retry-After` set) | check why Kuma sends so much |
| `502` | FCM unavailable/internal error/quota exceeded (after one retry with backoff; `Retry-After` set), or the relay could not authenticate to Google (bad/revoked key, wrong project id, FCM API disabled; no `Retry-After`) | transient: nothing; auth: check the relay log |

Retries: one retry for `5xx`, `UNAVAILABLE`, `INTERNAL` and network errors, after 1 s or FCM's
`Retry-After` if that is at most 5 s. `QUOTA_EXCEEDED` is not retried by the relay. A `401` from
FCM drops the cached access token and is retried once with a fresh one.

#### Endpoints

| Endpoint | Purpose |
|---|---|
| `POST /v1/fcm/{token}` | forward, see above |
| `GET /healthz` | `200 ok` when the service account is loaded. Does not call Google, so an FCM outage does not restart the container. |
| `GET /` | name and version, plain text |

`/relay healthcheck` (inside the container) GETs `/healthz` on loopback and exits 0/1; the image's
`HEALTHCHECK` and the compose example use it because distroless has no shell or curl.
`/relay version` prints the version.

#### Configuration

| Env | Default | |
|---|---|---|
| `FCM_SERVICE_ACCOUNT_FILE` | (required) | path to the service account JSON key; `project_id` is read from it |
| `FCM_PROJECT_ID` | from the key file | override, only if the key belongs to a different project than the app |
| `RELAY_TOKEN` | (required) | shared secret, at least 32 characters; the relay refuses to start otherwise |
| `LISTEN_ADDR` | `:8080` | |
| `MAX_BODY_BYTES` | `4096` | FCM rejects data payloads above 4096 bytes; a warning is logged if set higher |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `RATE_LIMIT_PER_MINUTE` | `60` | per client; `0` disables the limiter |
| `RATE_LIMIT_BURST` | `30` | |
| `TRUST_FORWARDED_FOR` | `false` | key the rate limit on the right-most `X-Forwarded-For` entry (the one Traefik appends). Set `true` behind Traefik, otherwise all requests share Traefik's IP. Leave `false` if the port is reachable without the proxy, since the header is then client-controlled. |

### Why every user needs their own Firebase project

Sending to an FCM token requires a credential of the Firebase project the app registered with.
A shared project would mean either a shared service account key (anyone with the app could send
pushes to every other user's phone) or a central server operated by someone else that sees every
alert. Instead:

- the **service account key stays on your server**, next to Uptime Kuma, and is only used by your
  relay;
- the app is configured with your project's public client config (see "App setup") and registers
  its token there;
- nobody but you (and Google, see the privacy note) can send to or read your devices' pushes.

FCM is free on the Spark plan; no billing account is needed.

### Setup

1. **Create a Firebase project.** <https://console.firebase.google.com> -> *Add project*. Any
   name; Google Analytics is not needed (turn it off). The free *Spark* plan is enough; do not
   add a billing account.
2. **Check that the FCM v1 API is enabled.** *Project settings* -> *Cloud Messaging*:
   "Firebase Cloud Messaging API (V1)" should say *Enabled* (default for new projects). The
   legacy API is not used.
3. **Create the service account key.** *Project settings* -> *Service accounts* -> *Firebase
   Admin SDK* -> *Generate new private key* -> *Generate key*. A JSON file
   (`<project>-firebase-adminsdk-xxxxx-<id>.json`) is downloaded. Treat it like a password: it
   grants admin access to the Firebase project. Do not commit it, do not put it on the phone.

   Optional, tighter: in the Google Cloud console (IAM & Admin -> Service accounts) create a
   dedicated service account with only the role *Firebase Cloud Messaging API Admin*
   (`roles/firebasecloudmessaging.admin`) and download a JSON key for it instead. The relay only
   needs permission to send messages. (Not tested by us; the default Admin SDK key is what was
   verified.)
4. **Store it on the server**, next to the compose file, readable only by the container user
   (distroless `nonroot`, uid 65532):

   ```sh
   install -d -m 700 ./fcm-relay
   sudo install -m 400 -o 65532 -g 65532 ~/<project>-firebase-adminsdk-*.json ./fcm-relay/service-account.json
   ```

   Rotate by generating a new key in the console, replacing the file, `docker compose restart
   fcm-relay`, then deleting the old key in the console.
5. **Generate the relay token** and put it into the stack's `.env`:

   ```sh
   echo "RELAY_TOKEN=$(openssl rand -hex 32)" >> .env
   echo "FCM_RELAY_HOST=fcm-relay.example.com" >> .env
   ```

6. **Add the service** to the Uptime Kuma compose stack. Full file:
   [`relay/docker-compose.example.yml`](../../relay/docker-compose.example.yml). Core of it:

   ```yaml
   services:
     fcm-relay:
       image: ghcr.io/kirkanos/uptime-kuma-fcm-relay:latest   # or a pinned version, e.g. :0.1.0
       container_name: ${SERVICE}-fcm-relay
       restart: unless-stopped
       environment:
         FCM_SERVICE_ACCOUNT_FILE: /secrets/service-account.json
         RELAY_TOKEN: ${RELAY_TOKEN:?set RELAY_TOKEN in .env}
         TRUST_FORWARDED_FOR: "true"
       volumes:
         - ./fcm-relay/service-account.json:/secrets/service-account.json:ro
       read_only: true
       cap_drop: [ALL]
       security_opt: ["no-new-privileges:true"]
       networks: [traefik-network]
       labels:
         - "traefik.enable=true"
         - "traefik.http.routers.${SERVICE}-fcm-relay.rule=Host(`${FCM_RELAY_HOST}`)"
         - "traefik.http.routers.${SERVICE}-fcm-relay.entrypoints=https"
         - "traefik.http.routers.${SERVICE}-fcm-relay.tls=true"
         - "traefik.http.routers.${SERVICE}-fcm-relay.tls.certresolver=default"
         - "traefik.http.services.${SERVICE}-fcm-relay.loadbalancer.server.port=8080"
       healthcheck:
         test: ["CMD", "/relay", "healthcheck"]
         interval: 60s
         timeout: 5s
         retries: 3
   ```

   The example file additionally has an `http` entrypoint router that redirects to HTTPS.
   Configure Kuma with the `https://` URL directly; do not rely on a redirected POST.

   The relay needs outbound HTTPS to `oauth2.googleapis.com` and `fcm.googleapis.com`.

7. **DNS / Traefik.** Point `FCM_RELAY_HOST` (A/AAAA or CNAME) at the Traefik host;
   the `default` cert resolver issues the certificate on first request. Then
   `docker compose up -d fcm-relay` and check `docker compose ps` shows `(healthy)` and the log
   has `msg="relay started" ... project_id=<your project>`.

   Alternative without a public host name: if Kuma and the relay share a Docker network, Kuma can
   call `http://fcm-relay:8080/v1/fcm/<token>` directly and the Traefik labels can be dropped
   (then leave `TRUST_FORWARDED_FOR` unset). The phone never talks to the relay; only Kuma does.

8. **Verify** from anywhere:

   ```sh
   curl -s https://fcm-relay.example.com/healthz
   # ok

   curl -s -o /dev/null -w '%{http_code}\n' -X POST -d '{}' \
     https://fcm-relay.example.com/v1/fcm/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   # 401   (no bearer token)

   curl -s -X POST -H "Authorization: Bearer $RELAY_TOKEN" -d '{}' \
     https://fcm-relay.example.com/v1/fcm/not-a-token
   # malformed FCM registration token   (400: auth OK, token check works)

   curl -s -X POST -H "Authorization: Bearer $RELAY_TOKEN" -d '{"v":1}' \
     https://fcm-relay.example.com/v1/fcm/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   # expected: 410 "FCM registration token is no longer valid (INVALID_ARGUMENT: ...)"
   #   -> the relay authenticated to Google and FCM answered. A 502 "relay could not
   #      authenticate to FCM (... invalid_grant ...)" instead means the key file is wrong
   #      or was deleted in the console.
   ```

   An end-to-end test with a real device token is done from the app (see "App setup") or with
   Kuma's *Test* button on the notification.

### Kuma notification

Identical to the UnifiedPush one ([README section 2](./README.md#2-exact-kuma-notification-object-socketio-addnotification))
except for URL and token:

```json
{
  "type": "webhook",
  "webhookURL": "https://fcm-relay.example.com/v1/fcm/<FCM registration token>",
  "httpMethod": "post",
  "webhookContentType": "custom",
  "webhookCustomBody": "<schema v1 template, unchanged>",
  "webhookAdditionalHeaders": "{\"Authorization\":\"Bearer <RELAY_TOKEN>\",\"Content-Type\":\"application/json\"}"
}
```

When a notification starts failing with `410`, the device's token is dead; the app re-registers
and replaces the notification, or the operator deletes it.

### Privacy

With this transport the alert content (monitor name, URL/host name, status, Kuma's message text)
passes through Google's FCM infrastructure in clear text (TLS in transit, but readable by Google).
The UnifiedPush/ntfy transport keeps everything on your own server. Use FCM only if that is
acceptable for your monitors; the template can be trimmed (e.g. drop `url`/`hostname`) if needed.

### Releases

The image is built by `.github/workflows/relay.yml` for `linux/amd64` and `linux/arm64` when a
tag `relay-vX.Y.Z` is pushed and published as `ghcr.io/kirkanos/uptime-kuma-fcm-relay:X.Y.Z` and
`:latest`. Pushes and pull requests touching `relay/` run gofmt, `go vet` and `go test -race`.

## App setup

Only the `full` APK (`uptime-kuma-android-<version>.apk`) has this option; the `foss` APK contains no
Firebase or Google Play services code at all and offers ntfy only.

### What the app does

```
Kuma webhook (same JSON body as for ntfy, WebhookTemplate schema v1)
  POST https://<relay>/v1/fcm/<FCM token>       Authorization: Bearer <RELAY_TOKEN>
      -> relay -> FCM HTTP v1, high priority, data = {"v": "1", "body": "<raw webhook body>"}
          -> KumaFirebaseMessagingService.onMessageReceived
              -> PushMessageHandler (shared with UnifiedPush): PushPayloadParser -> AlertNotifier
                 (dedupe, mutes, quiet hours, history) -> expedited SyncWorker
```

- **Nothing Firebase-specific is compiled in**: no `google-services.json`, no google-services Gradle plugin.
  Every user creates an own (free, Spark plan) Firebase project and enters its four public values in the
  app. The app then initializes the default `FirebaseApp` at runtime with
  `FirebaseApp.initializeApp(context, FirebaseOptions)`.
- **No automatic initialization**: `FirebaseInitProvider` is removed from the merged manifest
  (`tools:node="remove"` in `push-fcm/src/main/AndroidManifest.xml`). `PushInitializer` initializes
  Firebase from `Application.onCreate` (Android creates the messaging service only after that) whenever
  Firebase values are stored, so token refreshes and messages also work after a reboot.
- **No token before setup**: `firebase_messaging_auto_init_enabled=false` in the manifest. A successful
  setup turns FCM auto-init on (persisted by the SDK, needed for periodic token validation and rotation);
  switching to ntfy, disabling push or logging out deletes the token and turns it off again.
- **No analytics, no extra data**: only `firebase-messaging` (BoM 35.1.0, messaging 26.0.1) is included,
  `firebase_messaging_notification_delegation_enabled=false` (no Play services proxy notifications),
  `delivery_metrics_exported_to_big_query_enabled=false`, `firebase_analytics_collection_deactivated=true`.
- **Token changes**: `onNewToken` stores the new webhook URL (`<relay>/v1/fcm/<new token>`) and
  `EndpointUpdateWorker` writes it into the existing Kuma notification (retried until online), the same
  mechanism as for changed UnifiedPush endpoints.

### Firebase project (once)

1. [console.firebase.google.com](https://console.firebase.google.com) -> *Add project*. Google Analytics
   is not needed (switch it off).
2. *Project settings* -> *Your apps* -> *Add app* -> Android. **Package name**:
   - `net.kirkanos.uptimekuma` for release APKs (GitHub releases),
   - `net.kirkanos.uptimekuma.debug` for debug builds (`applicationIdSuffix ".debug"`).

   Register both if you use both. The app shows its own package name on the setup screen. No SHA-1 is
   needed. Skip the "add SDK" steps; the app already contains it.
3. Download `google-services.json` (*Project settings* -> *Your apps*) and copy it to the phone, or keep
   the settings page open to copy the values by hand.
4. For the relay: *Project settings* -> *Service accounts* -> *Generate new private key* (see
   [Relay](#relay)). This key stays on the relay and never goes into the app.

### In the app

Settings -> *Push notifications* -> *Firebase (own project)*:

| Field | Value | From google-services.json |
|---|---|---|
| Relay URL | `https://relay.example.com` (base URL, no query). `http://` works but is flagged: the relay token and alerts would travel unencrypted | |
| Relay token | the relay's `RELAY_TOKEN` (stored encrypted with the Android Keystore) | |
| Project ID | `kuma-push-1a2b3` | `project_info.project_id` |
| App ID | `1:123456789012:android:0123456789abcdef` | `client[].client_info.mobilesdk_app_id` |
| API key | `AIza...` (39 characters) | `client[].api_key[0].current_key` |
| Sender ID | `123456789012` | `project_info.project_number` |

*Import google-services.json* opens the system file picker (no storage permission) and fills the four
Firebase fields from the `client` entry whose `package_name` equals the app's package. If the file has no
such entry the app says which packages it contains: add an Android app with the exact package name in
Firebase and download the file again.

The values are checked before anything is sent: project id format, API key format (the same check as
Firebase Installations), numeric sender id, and the app id must contain the sender id (catches values of
two different projects). *Alert for all existing monitors* works as for ntfy (applied to all monitors and
Kuma's default for new ones).

*Set up* then: checks Google Play services, initializes Firebase, fetches the FCM token (30 s timeout),
creates the Kuma `webhook` notification **"<device> (Firebase)"** with URL `<relay>/v1/fcm/<token>` and
`Authorization: Bearer <relay token>`, and shows *Send test notification*. If ntfy was active before, its
Kuma notification is deleted and UnifiedPush unregistered afterwards (only once the Firebase setup
worked; a failed setup leaves ntfy untouched). The settings screen shows "Active via Firebase".

### Errors

| Message | Cause |
|---|---|
| Google Play services are not available | no Play services (e.g. de-Googled ROM): use ntfy |
| Google Play services need to be updated or enabled | `SERVICE_VERSION_UPDATE_REQUIRED`, `SERVICE_DISABLED`, `SERVICE_UPDATING` |
| Firebase Cloud Messaging is not reachable | `SERVICE_NOT_AVAILABLE`, `INTERNAL_SERVER_ERROR`, timeouts, offline |
| Firebase rejected the project values | `AUTHENTICATION_FAILED`, `FIS_AUTH_ERROR`, `INVALID_SENDER`, Firebase Installations `BAD CONFIG` (wrong API key, app id, project, or the Android app in Firebase has another package name) |
| Firebase is still running with the previous project values | see below |

### Changing the Firebase project

The SDK cannot switch projects inside a running process: a second `FirebaseApp.initializeApp` for the
default app throws `IllegalStateException("FirebaseApp name [DEFAULT] already exists!")`,
`FirebaseApp.delete()` is a hidden (`@hide`) API, and FCM keeps process-wide static state (token store,
executors). So when Firebase already runs with other values (also after a first attempt with wrong but
well-formed values), the app saves the new values and offers *Restart app*. After the restart Firebase
starts with the new values; tap *Set up* again. (If the Firebase option was already active, FCM auto-init
creates the new project's token right after the restart and the Kuma notification is updated through
`onNewToken` automatically.) Obviously malformed values never reach Firebase, so typos in them do not
need a restart.

### Not verifiable without a real Firebase project

The unit tests cover parsing, validation, URL building, transport switching, the shared message handler
and the setup screen state with a fake FCM gateway. Token fetch against Google, delivery through the
relay, `onNewToken` after rotation and the restart path with a real second project need a real Firebase
project and relay.
