#!/usr/bin/env node
// First-run setup for a fresh local Uptime Kuma, or plain login if it is already set up.
//
//   node setup.mjs              # uses KUMA_URL / KUMA_USER / KUMA_PASSWORD env or dev defaults
//
// Flow (Kuma 2.5.x):
//   connect -> server emits `info` (version hidden) and, on a fresh DB, `setup`
//   -> `needSetup` ack tells us whether an admin user must be created
//   -> `setup`(username, password) creates it
//   -> `login`({username,password}) returns { ok: true, token: <JWT> }
// Prints the JWT on stdout.

import { connect, ack, login, log, waitForServerReady, KUMA_URL, KUMA_USER, KUMA_PASSWORD } from "./lib.mjs";

const seen = [];
const socket = await connect({ onAny: (event, ...args) => seen.push({ event, args }) });
log(`connected to ${KUMA_URL} (socket id ${socket.id})`);

await waitForServerReady(socket); // handlers are registered only after the server's connection hook finishes
const needSetup = await ack(socket, "needSetup");
log(`needSetup -> ${needSetup}`);

if (needSetup) {
    const res = await ack(socket, "setup", KUMA_USER, KUMA_PASSWORD);
    log("setup ->", JSON.stringify(res));
    if (!res?.ok) {
        console.error("Setup failed; aborting.");
        process.exit(1);
    }
}

const res = await login(socket, { username: KUMA_USER, password: KUMA_PASSWORD });
log(`login ok as ${KUMA_USER}`);

// Mimic the browser's one-time timezone initialisation so `info.serverTimezone`
// is deterministic (Europe/Berlin by default; override with KUMA_SERVER_TZ).
if (seen.some((e) => e.event === "initServerTimezone")) {
    const tz = process.env.KUMA_SERVER_TZ ?? "Europe/Berlin";
    socket.emit("initServerTimezone", tz);
    log(`initServerTimezone -> ${tz}`);
    await new Promise((r) => setTimeout(r, 500));
}

const info = seen.filter((e) => e.event === "info").at(-1)?.args[0];
if (info) {
    log(`server info: version=${info.version} tz=${info.serverTimezone} offset=${info.serverTimezoneOffset} db=${info.dbType}`);
}

console.log(res.token);
socket.disconnect();
