# Local dev/test infrastructure

A throwaway Uptime Kuma 2.5.x instance (plus an ntfy server for notification payloads)
that the Android client can be developed and tested against, and a small Node project that
drives Kuma over its Socket.IO API to seed test data and record real payloads as fixtures.

Nothing here is meant for production. All credentials below are dev defaults.

## Stack

| Service | Image | URL | Notes |
|---|---|---|---|
| `kuma` (`kuma-dev`) | `louislam/uptime-kuma:2.5.8` | http://localhost:3001 | SQLite, data in `infra/.data/kuma` |
| `ntfy` (`kuma-dev-ntfy`) | `binwiederhier/ntfy:v2.34.0` | http://localhost:2586 | `deny-all` by default, data in `infra/.data/ntfy` |

`infra/.data/` is gitignored. Removing it resets both services to first-run state.

```bash
# from the repo root
docker compose -f infra/docker-compose.dev.yml up -d
docker compose -f infra/docker-compose.dev.yml ps          # wait until both are "healthy"
docker compose -f infra/docker-compose.dev.yml logs -f kuma
docker compose -f infra/docker-compose.dev.yml down         # stop; keeps infra/.data
rm -rf infra/.data                                           # full reset (while stopped)
```

`UPTIME_KUMA_DB_TYPE=sqlite` is set so Kuma 2.x skips its "choose a database" first-run page
and goes straight to the Socket.IO `setup` flow (create the first admin user).

### Default dev credentials

| What | Value |
|---|---|
| Kuma admin user | `admin` |
| Kuma admin password | `devpassword123!` |
| Kuma server timezone (set by `setup.mjs`) | `Europe/Berlin` (override with `KUMA_SERVER_TZ`) |
| ntfy | no users; `NTFY_AUTH_DEFAULT_ACCESS=deny-all` |

Kuma's seeded ntfy/webhook notifications point at `http://ntfy:80` (container network) and topic
`upTESTTOPIC`. With deny-all they are rejected with 403, which is fine: they exist so the
notification payload shapes exist. To actually receive them:

```bash
docker compose -f infra/docker-compose.dev.yml exec ntfy ntfy user add --role=admin admin   # prompts for a password
# then set the "Dev ntfy" notification in the Kuma UI to username/password auth, and
# subscribe at http://localhost:2586/upTESTTOPIC
```

## kuma-seed scripts

`infra/kuma-seed/` is a tiny Node (>= 20, tested with 26) project with a single dependency,
`socket.io-client` 4.x (Kuma 2.5.x runs Socket.IO 4 server side).

```bash
cd infra/kuma-seed
npm ci                       # or npm install
node setup.mjs               # first-run: creates admin / devpassword123!, otherwise logs in; prints the JWT
node seed.mjs                # creates monitors, tags, notifications, status page "dev", maintenance window
node record.mjs --duration 90 --twofa   # records fixtures into ../../fixtures/kuma-<version>/
```

All scripts honour `KUMA_URL` (default `http://localhost:3001`, `ws://` also accepted),
`KUMA_USER`, `KUMA_PASSWORD`.

### setup.mjs

Connects, asks `needSetup`; if true emits `setup(username, password)`, then `login` and prints the
JWT on stdout. It also answers Kuma's one-time `initServerTimezone` request like a browser would,
so `info.serverTimezone` is deterministic.

### seed.mjs

Idempotent-ish: monitors, tags, notifications are matched by name, the status page by slug
`dev`, the maintenance by title. Everything uses a 20 s interval (Kuma's minimum) so heartbeats
show up quickly. Seeded set:

| Monitor | Type | Purpose |
|---|---|---|
| HTTP example.com | `http` | plain UP monitor, TLS expiry on -> `certInfo` |
| HTTP keyword example.com | `keyword` | keyword match |
| JSON query Kuma entry-page | `json-query` | queries Kuma's own `/api/entry-page` (always reachable) |
| TCP example.com:443 | `port` | TCP |
| Ping 1.1.1.1 | `ping` | ICMP (may be DOWN if the Docker host blocks ICMP) |
| DNS example.com A | `dns` | DNS via 1.1.1.1 |
| Push demo | `push` | push monitor; `record.mjs` hits its push URL |
| Tagged HTTP example.org | `http` | tags `env:dev`, `team:android` |
| Failing HTTP localhost:9 | `http` | 1 retry -> PENDING then DOWN heartbeats; has both notifications attached |
| Under maintenance HTTP | `http` | covered by an active `single` maintenance -> status 3 heartbeats |
| Group Web | `group` | parent of the two children below |
| Group child HTTP example.com / Group child TCP example.com:80 | `http` / `port` | children of Group Web |

Plus notifications `Dev webhook` (type `webhook`) and `Dev ntfy` (type `ntfy`), a published status
page `/status/dev` with one group "Core" (HTTP example.com, TCP example.com:443), and the
maintenance "Dev maintenance (single)" linked to the maintenance monitor and the status page.

### record.mjs

Options: `--duration <s>` (listen time for live heartbeats, default 90), `--per-key <n>` (samples kept
per bucket, default 5), `--out <dir>` (default `../../fixtures`), `--twofa` (enable TOTP 2FA on the
admin, record the `tokenRequired` / TOTP login shapes, then disable it again), `--no-mutations`
(skip the add/edit/pause/resume/delete round trip on a temporary monitor).

Output goes to `fixtures/kuma-<version>/` where `<version>` is taken from the `info` event. The
script generates a `README.md` there listing every file, how it was produced and the timestamp.
Wait a minute or two after `seed.mjs` before recording so the failing monitor has gone through
PENDING -> DOWN and a few beats exist for `getMonitorBeats`.

## Ports

3001 (Kuma) and 2586 (ntfy) were chosen to coexist with other local stacks; 2587 is used by another
local project and is intentionally avoided.

## Running the app against the dev stack (emulator)

The Android emulator's app network (virtual Wi-Fi) cannot reach the host via `10.0.2.2`, only the
shell can. Forward the ports instead and use `http://localhost:3001` as server address in the app:

```bash
adb -s emulator-5554 reverse tcp:3001 tcp:3001
adb -s emulator-5554 reverse tcp:2586 tcp:2586
```

For the push chain the ntfy URL must be reachable from the Kuma container and the phone alike; use
the Mac's LAN IP (e.g. `http://192.168.x.y:2586`) as default server in the ntfy app. Create the dev
users once:

```bash
docker exec -e NTFY_PASSWORD=kumapass123 kuma-dev-ntfy ntfy user add kuma
docker exec -e NTFY_PASSWORD=apppass123 kuma-dev-ntfy ntfy user add app
docker exec kuma-dev-ntfy ntfy access kuma 'up*' write-only
docker exec kuma-dev-ntfy ntfy access app 'up*' read-only
docker exec kuma-dev-ntfy ntfy token add --label=dev kuma
```
