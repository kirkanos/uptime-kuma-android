# Push via Firebase Cloud Messaging (own Firebase project)

## Relay

<!-- Written separately (relay/ and its documentation); kept as a heading here so both parts merge cleanly. -->

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
