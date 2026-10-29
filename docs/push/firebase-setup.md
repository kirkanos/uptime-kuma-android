# Firebase push: step-by-step setup

This guide takes you from nothing to the first alert on your phone with the **Firebase** push option.
It is the short, linear version; the reference (relay responses, configuration, errors, design) is
[firebase.md](./firebase.md). For push via ntfy instead, see the [README](../../README.md#setup-ntfy).

```
Uptime Kuma --webhook--> FCM relay (your server) --> Firebase Cloud Messaging --> app
```

What you need:

- the `full` APK (`uptime-kuma-android-<version>.apk`; the `foss` APK has no Firebase),
- a phone with Google Play services,
- a Google account (for the Firebase console),
- the server where Uptime Kuma runs (Docker), for the relay,
- for a public relay: a host name and a reverse proxy with TLS (the examples use Traefik).

Cost: none. Firebase Cloud Messaging is free on the *Spark* plan; you never add a billing account.

Time: about 15 minutes.

What you end up with, and where it goes:

| File / value | From | Goes to | Secret? |
|---|---|---|---|
| `google-services.json` | Firebase console, step 1.3 | the app (import) | no (public client config) |
| `<project>-firebase-adminsdk-fbsvc-<id>.json` | Firebase console, step 1.5 | the relay, on your server only | **yes**: admin access to the Firebase project |
| `RELAY_TOKEN` | you generate it, step 2.2 | relay `.env` and the app | **yes** |

Never commit either JSON file or the token, and never put the service account key on the phone.

## 1. Firebase project (Firebase console)

### 1.1 Create the project

1. Open <https://console.firebase.google.com> and sign in with your Google account.
2. *Create a project* (or *Add project*).
3. Name: anything, e.g. `uptime-kuma`. Firebase appends a random suffix to the project ID
   (`uptime-kuma-1a2b3`); that is fine.
4. Accept the terms. Turn off *Gemini in Firebase* if asked; it is not needed.
5. **Google Analytics: turn it off.** The app does not use it.
6. *Create project*, wait, *Continue*.

The project starts on the free *Spark* plan. Do not upgrade it.

### 1.2 Register the app (Android)

1. On the project overview page click the **Android** icon (*Add app* -> Android).
2. **Android package name**, exactly:
   - `net.kirkanos.uptimekuma` for the APKs from GitHub Releases,
   - `net.kirkanos.uptimekuma.debug` additionally, if you also run a debug build you built yourself.

   The push setup screen in the app shows its own package name, if in doubt.
3. App nickname: optional. **Debug signing certificate SHA-1: leave empty**, FCM does not need it.
4. *Register app*.
5. *Download google-services.json*: you can do this now or in 1.3.
6. Skip "Add Firebase SDK" and the remaining steps (*Next*, *Continue to console*); the app already
   contains the SDK.

For the debug package repeat 1.2 with `net.kirkanos.uptimekuma.debug`.

### 1.3 Download `google-services.json`

Gear icon next to *Project overview* -> *Project settings* -> *General* -> *Your apps* -> the Android
app -> *google-services.json*.

Download it **after** you registered all packages: the file then contains one `client` entry per
package, and the app picks the entry for its own package automatically. A file downloaded before the
second package was added only contains the first one (the app then tells you which packages it found).

The file holds only public identifiers (project ID, project number, app ID, API key). It is safe to
copy it to the phone, e.g. into *Downloads*.

### 1.4 Check that the FCM API is enabled

*Project settings* -> *Cloud Messaging*: **Firebase Cloud Messaging API (V1)** must say *Enabled*.
That is the default for new projects. The *legacy* API is not used; leave it disabled.

### 1.5 Create the service account key (for the relay)

*Project settings* -> *Service accounts* -> *Firebase Admin SDK* -> **Generate new private key** ->
*Generate key*.

A file like `uptime-kuma-1a2b3-firebase-adminsdk-fbsvc-0123456789.json` is downloaded. This key lets
anyone send messages to your devices and administer the project: keep it only on the relay server,
delete the downloaded copy afterwards.

If the button reports that key creation is disabled, your Google account belongs to an organization
whose policy `iam.disableServiceAccountKeyCreation` forbids keys (common for Google Workspace
accounts). Use a personal Google account for the Firebase project, or ask your admin for an
exception for this project.

To revoke a key later: Google Cloud console -> *IAM & Admin* -> *Service accounts* ->
`firebase-adminsdk-...` -> *Keys*.

## 2. Relay (your server)

The relay is a small stateless container next to Uptime Kuma. Kuma posts every alert to it, the relay
signs the request with the service account key and hands it to FCM.

### 2.1 Copy the service account key to the server

Next to the stack's `docker-compose.yml`, readable only by the container user (uid 65532):

```sh
scp ~/Downloads/<project>-firebase-adminsdk-fbsvc-*.json server:/tmp/sa.json
# on the server, in the stack directory:
install -d -m 700 ./fcm-relay
sudo install -m 400 -o 65532 -g 65532 /tmp/sa.json ./fcm-relay/service-account.json
rm /tmp/sa.json
```

Then delete the key from your *Downloads* folder.

### 2.2 Relay token and host name

```sh
echo "RELAY_TOKEN=$(openssl rand -hex 32)" >> .env
echo "FCM_RELAY_HOST=fcm-relay.example.com" >> .env
```

If the stack keeps its `.env` encrypted (e.g. with sops), add both values there instead. Note the
token; you will type it into the app.

### 2.3 Add the service

Copy the `fcm-relay` service from
[`relay/docker-compose.example.yml`](../../relay/docker-compose.example.yml) into the stack's
`docker-compose.yml` (it uses `${SERVICE}`, `${FCM_RELAY_HOST}`, `${RELAY_TOKEN}` and the Traefik
network `traefik-network`; adjust to your proxy). Pin the image version
(`ghcr.io/kirkanos/uptime-kuma-fcm-relay:<version>`) instead of `latest`.

Create a DNS record for `FCM_RELAY_HOST` pointing at the proxy, then:

```sh
docker compose up -d fcm-relay
docker compose ps fcm-relay      # (healthy)
docker compose logs fcm-relay    # msg="relay started" ... project_id=<your project id>
```

The relay needs outbound HTTPS to `oauth2.googleapis.com` and `fcm.googleapis.com`.

Without a public host name: Kuma can reach the relay over a shared Docker network,
`http://fcm-relay:8080`. Only Kuma talks to the relay, never the phone. Drop the Traefik labels and
`TRUST_FORWARDED_FOR` then.

### 2.4 Check the relay

```sh
curl -s https://fcm-relay.example.com/healthz
# ok

curl -s -w ' %{http_code}\n' -X POST -H "Authorization: Bearer $RELAY_TOKEN" -d '{"v":1}' \
  https://fcm-relay.example.com/v1/fcm/$(printf 'x%.0s' $(seq 120))
# FCM registration token is no longer valid (INVALID_ARGUMENT: ...) 410
```

The `410` is the expected answer for this fake device token: it proves the relay authenticated to
Google and FCM answered. A `502 ... could not authenticate to FCM` means the key file is wrong,
deleted in the console, or belongs to another project. A `401` means the token in the header does not
match `RELAY_TOKEN`.

## 3. App (phone)

1. Install the `full` APK and log in to your Uptime Kuma.
2. Push setup (at the end of the login, or *Settings* -> *Push notifications*) -> **Firebase (own
   project)**.
3. **Relay URL**: `https://fcm-relay.example.com` (the base URL only; with a Docker-internal relay
   `http://fcm-relay:8080`, the address *Kuma* uses). `http://` shows a warning, which is expected
   for a relay that is only reachable inside your network.
4. **Relay token**: the `RELAY_TOKEN` from 2.2.
5. **Import google-services.json** and pick the file. The four Firebase fields (project ID, app ID, API
   key, sender ID) are filled in. Alternatively type them in by hand, see the table in
   [firebase.md](./firebase.md#in-the-app).
6. *Alert for all existing monitors*: on, unless you want to attach the notification to monitors one by
   one in Kuma.
7. **Set up**. The app fetches its FCM token and creates the Kuma notification
   *"<device name> (Firebase)"*. The screen then says "Push notifications are active".
8. **Send test notification**: a notification "<device name> (Firebase) Testing" arrives within
   seconds.
9. Allow the app to run unrestricted in the battery settings if alerts arrive late (normally not
   needed: FCM high-priority messages wake the app in Doze).

The app can be closed now; alerts still arrive.

## 4. Verify with a real alert

Create a *Push* monitor in Kuma (heartbeat interval 20 s) and use its push URL:

```sh
curl "https://uptime.example.com/api/push/<push token>?status=up&msg=test"   # -> "UP" alert
# send nothing for 20 s                                                       # -> "DOWN" alert
```

Both arrive with the app in the background. The relay log shows one `msg=forwarded` line per
alert. Delete the test monitor afterwards.

Measured on a Pixel 8 Pro (2026-10-29): Kuma marked the monitor DOWN at 17:13:54.84, the relay
forwarded it at 17:13:55.00, the notification was on the phone right after.

## 5. Troubleshooting

| Symptom | Cause, fix |
|---|---|
| Import says the file has no entry for the package | Register the app's exact package name in Firebase (1.2), download the file again (1.3). |
| "Firebase rejected the project values" | Values of another project, or the Android app in Firebase has another package name. Re-import the current file. |
| "Firebase is still running with the previous project values" | Changing the project needs an app restart; tap *Restart app*, then *Set up* again. |
| "Google Play services are not available" | Phone without Play services: use ntfy instead. |
| Test notification never arrives, relay log shows nothing | Kuma cannot reach the relay URL. Check from the Kuma container, e.g. `docker compose exec uptime-kuma curl -s <relay URL>/healthz`. |
| Relay log: `401` | Relay token in the app differs from `RELAY_TOKEN`. *Set up again* with the right token. |
| Relay log: `token_gone` / Kuma shows `410` | The app was reinstalled or its data cleared. Open the app and run the push setup again; delete the old notification in Kuma. |
| Relay log: `SENDER_ID_MISMATCH` / `403` | The app and the relay use different Firebase projects. Use the key and the `google-services.json` of the same project. |
| Relay log: `could not authenticate to FCM` | Service account key deleted or wrong; create a new one (1.5), replace the file, `docker compose restart fcm-relay`. |

## 6. Switching, removing

- *Settings* -> *Push notifications* -> *Change push method* switches to ntfy; the Firebase
  notification in Kuma is removed and the FCM token deleted once ntfy works.
- *Disable push* or logging out removes the device's notification from Kuma.
- To get rid of everything: delete the Firebase project in the console (*Project settings* ->
  *General* -> *Delete project*) and remove the `fcm-relay` service and `./fcm-relay/` from the stack.

## Privacy

With Firebase the alert content (monitor name, host name or URL, status, Kuma's message) passes
through Google's servers. If that is not acceptable for some monitors, use ntfy, which keeps
everything on your own server.
