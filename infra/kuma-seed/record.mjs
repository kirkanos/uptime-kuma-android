#!/usr/bin/env node
// Record real Uptime Kuma 2.5.x payloads as JSON fixtures for the Android client.
//
//   node record.mjs [--duration 90] [--per-key 5] [--out ../../fixtures] [--twofa] [--no-mutations]
//
// What it does, in order:
//   1. connects (before login) and captures every server->client event via onAny
//   2. records acks for: needSetup, login (wrong password), login (ok), loginByToken (ok / invalid)
//   3. drives the authenticated API and records each ack (getMonitor, getMonitorBeats,
//      getMonitorChartData, getSettings, getTags, maintenance/status page getters, add ok /
//      add validation error, editMonitor, pauseMonitor, resumeMonitor, deleteMonitor, ...)
//   4. hits the public REST endpoints (status page, heartbeat, entry-page, push, /metrics)
//   5. keeps listening for `--duration` seconds so heartbeat / uptime / avgPing / certInfo arrive
//   6. optionally (--twofa) enables TOTP 2FA, records the `tokenRequired` login shape and a
//      TOTP login, then disables 2FA again
//   7. writes fixtures/kuma-<version>/{<event>.json, ack/*.json, rest/*.json, README.md}
//
// Secrets (JWT, passwords, push tokens, 2FA secrets, API keys) are redacted before writing.

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import {
    connect,
    ack,
    waitFor,
    waitForServerReady,
    log,
    parseArgs,
    sleep,
    genSecret,
    KUMA_URL,
    KUMA_USER,
    KUMA_PASSWORD,
} from "./lib.mjs";

const args = parseArgs();
const DURATION_S = Number(args.duration ?? 90);
const PER_KEY = Number(args["per-key"] ?? 5);
const MAX_KEYS = Number(args["max-keys"] ?? 60);
const WITH_2FA = Boolean(args.twofa);
const MUTATIONS = !args["no-mutations"];
const here = path.dirname(fileURLToPath(import.meta.url));
const OUT_ROOT = path.resolve(here, args.out ?? "../../fixtures");
const STATUS_PAGE_SLUG = args.slug ?? "dev";
const startedAt = new Date();

// ---------------------------------------------------------------------------
// Redaction
// ---------------------------------------------------------------------------

const SENSITIVE_KEY = /(token|password|passwd|secret|api_?key|accesstoken|jwt)/i;
const KEEP_KEYS = new Set(["tokenRequired", "ntfyAuthenticationMethod", "sshAuthMethod"]);

/** Concrete secret strings seen during the run (dev password, JWTs, push tokens); scrubbed wherever they appear. */
const knownSecrets = new Set([KUMA_PASSWORD]);

function scrubKnown(str) {
    let out = str;
    for (const s of knownSecrets) {
        if (s && out.includes(s)) {
            out = out.split(s).join(`REDACTED(len=${s.length})`);
        }
    }
    return out;
}

function redact(value, key = "") {
    if (Array.isArray(value)) {
        return value.map((v) => redact(v));
    }
    if (value && typeof value === "object") {
        const out = {};
        for (const [k, v] of Object.entries(value)) {
            out[k] = redact(v, k);
        }
        return out;
    }
    if (typeof value === "string" && value.length > 0) {
        if (key === "uri" && value.startsWith("otpauth://")) {
            return value.replace(/secret=[^&]+/, "secret=REDACTED");
        }
        if (SENSITIVE_KEY.test(key) && !KEEP_KEYS.has(key)) {
            return `REDACTED(len=${value.length})`;
        }
        if (/^eyJ[\w-]+\.[\w-]+\.[\w-]+$/.test(value)) {
            return "REDACTED_JWT";
        }
        return scrubKnown(value);
    }
    return value;
}

// ---------------------------------------------------------------------------
// Event capture
// ---------------------------------------------------------------------------

/** How to bucket samples so we keep variety (e.g. heartbeats of every status). */
const EVENT_KEY = {
    heartbeat: (a) => `status=${a[0]?.status}:monitor=${a[0]?.monitorID}`,
    heartbeatList: (a) => `monitor=${a[0]}`,
    importantHeartbeatList: (a) => `monitor=${a[0]}`,
    uptime: (a) => `period=${a[1]}`,
    avgPing: (a) => `monitor=${a[0]}`,
    certInfo: (a) => `monitor=${a[0]}`,
    domainInfo: (a) => `monitor=${a[0]}`,
    updateMonitorIntoList: (a) => `monitor=${Object.keys(a[0] ?? {}).join(",")}`,
    info: (a) => (a[0]?.version ? "full" : "hidden-version"),
};

/** event -> Map(key -> samples[]) */
const events = new Map();
const eventCounts = new Map();
let kumaVersion = null;

function captureEvent(event, ...eventArgs) {
    eventCounts.set(event, (eventCounts.get(event) ?? 0) + 1);
    if (event === "info" && eventArgs[0]?.version) {
        kumaVersion = eventArgs[0].version;
    }
    const keyFn = EVENT_KEY[event] ?? (() => "all");
    const key = keyFn(eventArgs);
    let buckets = events.get(event);
    if (!buckets) {
        buckets = new Map();
        events.set(event, buckets);
    }
    let samples = buckets.get(key);
    if (!samples) {
        if (buckets.size >= MAX_KEYS) {
            return;
        }
        samples = [];
        buckets.set(key, samples);
    }
    if (samples.length >= PER_KEY) {
        return;
    }
    samples.push({ receivedAt: new Date().toISOString(), key, args: eventArgs });
}

// ---------------------------------------------------------------------------
// Ack capture
// ---------------------------------------------------------------------------

const acks = []; // { name, event, request, response, recordedAt }

async function recordAck(name, socket, event, ...reqArgs) {
    const t0 = Date.now();
    let response;
    try {
        response = await ack(socket, event, ...reqArgs);
    } catch (e) {
        response = { __error: e.message };
    }
    acks.push({
        name,
        event,
        request: reqArgs,
        // `null` + callbackArgs 0 means the server invoked the callback without arguments (e.g. logout)
        response: response === undefined ? null : response,
        callbackArgs: response === undefined ? 0 : 1,
        recordedAt: new Date().toISOString(),
        durationMs: Date.now() - t0,
    });
    const ok = response?.ok ?? response?.tokenRequired ?? (response === undefined ? "void" : "?");
    log(`ack ${name.padEnd(36)} ${event} -> ok=${ok}${response?.msg ? ` msg=${response.msg}` : ""}`);
    return response;
}

// ---------------------------------------------------------------------------
// REST capture
// ---------------------------------------------------------------------------

const rests = []; // { name, method, url, status, contentType, body }

async function recordRest(name, urlPath, init = {}) {
    const url = KUMA_URL + urlPath;
    const res = await fetch(url, init);
    const contentType = res.headers.get("content-type") ?? "";
    const text = await res.text();
    let body = text;
    if (contentType.includes("application/json")) {
        try {
            body = JSON.parse(text);
        } catch {
            /* keep text */
        }
    }
    rests.push({
        name,
        method: init.method ?? "GET",
        url: urlPath,
        requestHeaders: init.headers ? Object.keys(init.headers) : [],
        status: res.status,
        contentType,
        body,
        recordedAt: new Date().toISOString(),
    });
    log(`rest ${name.padEnd(36)} ${init.method ?? "GET"} ${urlPath} -> ${res.status} ${contentType.split(";")[0]}`);
    return { status: res.status, body };
}

// ---------------------------------------------------------------------------
// TOTP (RFC 6238, SHA-1, 30 s) for the optional 2FA path
// ---------------------------------------------------------------------------

function base32Decode(s) {
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let bits = "";
    for (const c of s.replace(/=+$/, "").toUpperCase()) {
        const v = alphabet.indexOf(c);
        if (v === -1) {
            throw new Error("invalid base32");
        }
        bits += v.toString(2).padStart(5, "0");
    }
    const bytes = [];
    for (let i = 0; i + 8 <= bits.length; i += 8) {
        bytes.push(parseInt(bits.slice(i, i + 8), 2));
    }
    return Buffer.from(bytes);
}

function totp(secretBytes, time = Date.now(), step = 30, digits = 6) {
    const counter = Buffer.alloc(8);
    counter.writeBigUInt64BE(BigInt(Math.floor(time / 1000 / step)));
    const hmac = crypto.createHmac("sha1", secretBytes).update(counter).digest();
    const offset = hmac[hmac.length - 1] & 0x0f;
    const code = ((hmac[offset] & 0x7f) << 24) | (hmac[offset + 1] << 16) | (hmac[offset + 2] << 8) | hmac[offset + 3];
    return String(code % 10 ** digits).padStart(digits, "0");
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

const live = {}; // latest value of list events, for picking ids
const socket = await connect({
    onAny: (event, ...a) => {
        captureEvent(event, ...a);
        if (["monitorList", "notificationList", "maintenanceList", "statusPageList", "info"].includes(event)) {
            live[event] = a[0];
        }
        if (event === "updateMonitorIntoList" && live.monitorList) {
            Object.assign(live.monitorList, a[0]);
        }
        if (event === "deleteMonitorFromList" && live.monitorList) {
            delete live.monitorList[a[0]];
        }
    },
});
log(`connected to ${KUMA_URL}`);
// Kuma registers its socket handlers only after an awaited sendInfo(); emitting earlier can be dropped.
log(`server ready: ${await waitForServerReady(socket)}`);

// -- public / auth acks -----------------------------------------------------
await recordAck("needSetup", socket, "needSetup");
await recordAck("login.wrong-password", socket, "login", { username: KUMA_USER, password: "definitely-wrong" });
await recordAck("login.unknown-user", socket, "login", { username: "nobody", password: "x" });
const loginRes = await recordAck("login.ok", socket, "login", { username: KUMA_USER, password: KUMA_PASSWORD });
if (!loginRes?.ok) {
    console.error("login failed, aborting:", loginRes);
    process.exit(1);
}
const jwt = loginRes.token;
knownSecrets.add(jwt);
await waitFor(socket, "monitorList", () => true, 10_000).catch(() => {});
await sleep(1500); // let the afterLogin burst (lists, heartbeatList per monitor, stats) land

// loginByToken on a second socket (its broadcast events are not captured)
{
    const s2 = await connect();
    await waitForServerReady(s2);
    await recordAck("loginByToken.ok", s2, "loginByToken", jwt);
    await sleep(500);
    await recordAck("logout", s2, "logout");
    s2.disconnect();
    const s3 = await connect();
    await waitForServerReady(s3);
    await recordAck("loginByToken.invalid", s3, "loginByToken", "eyJhbGciOiJIUzI1NiJ9.invalid.invalid");
    s3.disconnect();
}

// -- pick ids from the live monitor list -----------------------------------
const monitors = Object.values(live.monitorList ?? {});
for (const m of monitors) {
    if (m.pushToken) {
        knownSecrets.add(m.pushToken);
    }
}
const byType = (t) => monitors.find((m) => m.type === t);
const byName = (n) => monitors.find((m) => m.name === n);
const httpMon = byName("HTTP example.com") ?? byType("http") ?? monitors[0];
const groupMon = byType("group");
const pushMon = byType("push");
const failingMon = byName("Failing HTTP localhost:9");
const taggedMon = monitors.find((m) => m.tags?.length);
log(`monitors: ${monitors.length}; http=${httpMon?.id} group=${groupMon?.id} push=${pushMon?.id} failing=${failingMon?.id} tagged=${taggedMon?.id}`);

// -- read-only acks ----------------------------------------------------------
await recordAck("getMonitorList", socket, "getMonitorList");
if (httpMon) {
    await recordAck("getMonitor.http", socket, "getMonitor", httpMon.id);
    await recordAck("getMonitorBeats.24h", socket, "getMonitorBeats", httpMon.id, 24);
    await recordAck("getMonitorBeats.1h", socket, "getMonitorBeats", httpMon.id, 1);
    await recordAck("getMonitorBeats.invalid-period", socket, "getMonitorBeats", httpMon.id, null);
    await recordAck("getMonitorChartData.24h", socket, "getMonitorChartData", httpMon.id, 24);
    await recordAck("getMonitorChartData.720h", socket, "getMonitorChartData", httpMon.id, 720);
    await recordAck("getMonitorChartData.1y", socket, "getMonitorChartData", httpMon.id, 24 * 365);
    await recordAck("monitorImportantHeartbeatListCount.one", socket, "monitorImportantHeartbeatListCount", httpMon.id);
    await recordAck("monitorImportantHeartbeatListPaged.one", socket, "monitorImportantHeartbeatListPaged", httpMon.id, 0, 25);
}
await recordAck("monitorImportantHeartbeatListCount.all", socket, "monitorImportantHeartbeatListCount", null);
await recordAck("monitorImportantHeartbeatListPaged.all", socket, "monitorImportantHeartbeatListPaged", null, 0, 25);
if (groupMon) {
    await recordAck("getMonitor.group", socket, "getMonitor", groupMon.id);
}
if (pushMon) {
    await recordAck("getMonitor.push", socket, "getMonitor", pushMon.id);
}
if (failingMon) {
    await recordAck("getMonitor.failing", socket, "getMonitor", failingMon.id);
    await recordAck("getMonitorBeats.failing-24h", socket, "getMonitorBeats", failingMon.id, 24);
}
if (taggedMon) {
    await recordAck("getMonitor.tagged", socket, "getMonitor", taggedMon.id);
}
await recordAck("getMonitor.not-found", socket, "getMonitor", 999999);
await recordAck("getSettings", socket, "getSettings");
await recordAck("getTags", socket, "getTags");
await recordAck("twoFAStatus", socket, "twoFAStatus");
await recordAck("getAPIKeyList", socket, "getAPIKeyList");
await recordAck("getPushExample.curl", socket, "getPushExample", "curl");
await recordAck("checkApprise", socket, "checkApprise");

const maintenances = Object.values(live.maintenanceList ?? {});
await recordAck("getMaintenanceList", socket, "getMaintenanceList");
if (maintenances[0]) {
    await recordAck("getMaintenance", socket, "getMaintenance", maintenances[0].id);
    await recordAck("getMonitorMaintenance", socket, "getMonitorMaintenance", maintenances[0].id);
    await recordAck("getMaintenanceStatusPage", socket, "getMaintenanceStatusPage", maintenances[0].id);
}
await recordAck("getStatusPage.ok", socket, "getStatusPage", STATUS_PAGE_SLUG);
await recordAck("getStatusPage.not-found", socket, "getStatusPage", "does-not-exist");
await recordAck("getIncidentHistory", socket, "getIncidentHistory", STATUS_PAGE_SLUG, null);

// -- mutating acks (temp monitor, cleaned up) -------------------------------
if (MUTATIONS) {
    const tempName = `Recorder temp HTTP ${genSecret(6)}`;
    const tempSpec = {
        type: "http",
        name: tempName,
        parent: null,
        url: "https://example.com/?recorder=1",
        method: "GET",
        interval: 20,
        retryInterval: 20,
        resendInterval: 0,
        maxretries: 0,
        timeout: 16,
        notificationIDList: {},
        ignoreTls: false,
        upsideDown: false,
        expiryNotification: false,
        domainExpiryNotification: true,
        maxredirects: 10,
        accepted_statuscodes: ["200-299"],
        dns_resolve_type: "A",
        dns_resolve_server: "1.1.1.1",
        docker_container: "",
        docker_host: null,
        proxyId: null,
        httpBodyEncoding: "json",
        kafkaProducerBrokers: [],
        kafkaProducerSaslOptions: { mechanism: "None" },
        rabbitmqNodes: [],
        conditions: [],
        saveResponse: false,
        saveErrorResponse: true,
        responseMaxLength: 1024,
    };
    // NB: Kuma 2.5.8's server-side MIN_INTERVAL_SECOND is 1 (the 20 s minimum is frontend-only),
    // so use 0 to provoke "Interval cannot be less than 1 seconds".
    for (const [name, spec] of [
        ["add.validation-error.interval", { ...tempSpec, interval: 0 }],
        ["add.validation-error.statuscodes", { ...tempSpec, accepted_statuscodes: [200] }],
        ["add.validation-error.pm2-name", { ...tempSpec, type: "pm2", system_service_name: "" }],
    ]) {
        const r = await recordAck(name, socket, "add", spec);
        if (r?.ok && r.monitorID) {
            log(`  unexpected: ${name} created monitor ${r.monitorID}; deleting it`);
            await ack(socket, "deleteMonitor", r.monitorID, false);
        }
    }
    const added = await recordAck("add.ok", socket, "add", tempSpec);
    if (added?.ok) {
        const id = added.monitorID;
        await sleep(500);
        await recordAck("editMonitor.ok", socket, "editMonitor", { ...tempSpec, id, name: tempName + " (edited)", interval: 30, retryInterval: 30, timeout: 24 });
        await recordAck("editMonitor.validation-error", socket, "editMonitor", { ...tempSpec, id, interval: 0 });
        await recordAck("pauseMonitor.ok", socket, "pauseMonitor", id);
        await sleep(300);
        await recordAck("resumeMonitor.ok", socket, "resumeMonitor", id);
        await sleep(300);
        if (taggedMon) {
            const { tags } = await ack(socket, "getTags");
            if (tags?.[0]) {
                await recordAck("addMonitorTag.ok", socket, "addMonitorTag", tags[0].id, id, "recorder");
                await recordAck("deleteMonitorTag.ok", socket, "deleteMonitorTag", tags[0].id, id, "recorder");
            }
        }
        await recordAck("clearEvents.ok", socket, "clearEvents", id);
        await recordAck("deleteMonitor.ok", socket, "deleteMonitor", id, false);
        await recordAck("deleteMonitor.not-found", socket, "deleteMonitor", id, false);
    }
    await recordAck("pauseMonitor.not-found", socket, "pauseMonitor", 999999);
}

// -- public REST -------------------------------------------------------------
const basic = "Basic " + Buffer.from(`${KUMA_USER}:${KUMA_PASSWORD}`).toString("base64");
await recordRest("entry-page", "/api/entry-page");
await recordRest("status-page", `/api/status-page/${STATUS_PAGE_SLUG}`);
await recordRest("status-page.not-found", "/api/status-page/does-not-exist");
await recordRest("status-page.heartbeat", `/api/status-page/heartbeat/${STATUS_PAGE_SLUG}`);
await recordRest("status-page.manifest", `/api/status-page/${STATUS_PAGE_SLUG}/manifest.json`);
await recordRest("status-page.incident-history", `/api/status-page/${STATUS_PAGE_SLUG}/incident-history`);
await recordRest("metrics.basic-auth", "/metrics", { headers: { Authorization: basic } });
await recordRest("metrics.unauthorized", "/metrics");
if (httpMon) {
    await recordRest("badge.status", `/api/badge/${httpMon.id}/status`);
    await recordRest("badge.uptime-24h", `/api/badge/${httpMon.id}/uptime/24`);
}
if (pushMon?.pushToken) {
    await recordRest("push.up", `/api/push/${pushMon.pushToken}?status=up&msg=OK&ping=42`);
    await recordRest("push.down", `/api/push/${pushMon.pushToken}?status=down&msg=recorder+says+down`);
    await recordRest("push.invalid-token", "/api/push/not-a-real-token?status=up");
}

// -- listen for live heartbeats ---------------------------------------------
log(`listening ${DURATION_S}s for live heartbeats / stats ...`);
const deadline = Date.now() + DURATION_S * 1000;
while (Date.now() < deadline) {
    await sleep(5000);
    const hb = events.get("heartbeat");
    const statuses = hb ? [...hb.keys()].join(" ") : "-";
    log(`  heartbeats so far: ${eventCounts.get("heartbeat") ?? 0} (${statuses}); uptime ${eventCounts.get("uptime") ?? 0}; avgPing ${eventCounts.get("avgPing") ?? 0}; certInfo ${eventCounts.get("certInfo") ?? 0}`);
}

// -- optional 2FA round trip --------------------------------------------------
let twofaNote = "not recorded (run with --twofa)";
if (WITH_2FA) {
    try {
        const prep = await recordAck("prepare2FA.ok", socket, "prepare2FA", KUMA_PASSWORD);
        const secretB32 = /secret=([^&]+)/.exec(prep?.uri ?? "")?.[1];
        if (!secretB32) {
            throw new Error("prepare2FA returned no otpauth uri");
        }
        const secretBytes = base32Decode(secretB32);
        await recordAck("verifyToken.ok", socket, "verifyToken", totp(secretBytes), KUMA_PASSWORD);
        await recordAck("verifyToken.invalid", socket, "verifyToken", "000000", KUMA_PASSWORD);
        await recordAck("save2FA.ok", socket, "save2FA", KUMA_PASSWORD);
        await recordAck("twoFAStatus.enabled", socket, "twoFAStatus");
        {
            const s4 = await connect();
            await waitForServerReady(s4);
            await recordAck("login.2fa-token-required", s4, "login", { username: KUMA_USER, password: KUMA_PASSWORD });
            await recordAck("login.2fa-invalid-token", s4, "login", { username: KUMA_USER, password: KUMA_PASSWORD, token: "000000" });
            // wait for a fresh 30 s window so the token differs from any previously used one
            const code = totp(secretBytes, Date.now() + 30_000);
            await sleep(30_000 - (Date.now() % 30_000) + 500);
            await recordAck("login.2fa-ok", s4, "login", { username: KUMA_USER, password: KUMA_PASSWORD, token: code });
            s4.disconnect();
        }
        twofaNote = "recorded (prepare2FA, verifyToken, save2FA, login tokenRequired / invalid / ok, disable2FA)";
    } catch (e) {
        twofaNote = `partially recorded; error: ${e.message}`;
        log("2FA recording error:", e.message);
    } finally {
        await recordAck("disable2FA.ok", socket, "disable2FA", KUMA_PASSWORD);
        await recordAck("twoFAStatus.disabled", socket, "twoFAStatus");
    }
}

socket.disconnect();

// ---------------------------------------------------------------------------
// Write fixtures
// ---------------------------------------------------------------------------

const version = kumaVersion ?? live.info?.version ?? "unknown";
const outDir = path.join(OUT_ROOT, `kuma-${version}`);
fs.mkdirSync(path.join(outDir, "ack"), { recursive: true });
fs.mkdirSync(path.join(outDir, "rest"), { recursive: true });

const meta = {
    kumaVersion: version,
    recordedAt: startedAt.toISOString(),
    recorder: "infra/kuma-seed/record.mjs",
    recorderArgs: process.argv.slice(2),
    kumaUrl: KUMA_URL,
};

function writeJson(file, data) {
    fs.writeFileSync(file, JSON.stringify(redact(data), null, 2) + "\n");
}

const written = [];
for (const [event, buckets] of [...events.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    const samples = [...buckets.values()].flat().sort((a, b) => a.receivedAt.localeCompare(b.receivedAt));
    const file = path.join(outDir, `${event}.json`);
    writeJson(file, {
        ...meta,
        event,
        direction: "server->client",
        argsDescription: `Positional Socket.IO args of each "${event}" emit, in order.`,
        totalReceived: eventCounts.get(event),
        sampleKeys: [...buckets.keys()],
        samples,
    });
    written.push({ file: `${event}.json`, kind: "event", count: samples.length, total: eventCounts.get(event) });
}
for (const a of acks) {
    const file = path.join(outDir, "ack", `${a.name}.json`);
    writeJson(file, { ...meta, direction: "client->server emit + ack callback", ...a });
    written.push({ file: `ack/${a.name}.json`, kind: "ack", event: a.event, ok: a.response?.ok });
}
for (const r of rests) {
    const file = path.join(outDir, "rest", `${r.name}.json`);
    writeJson(file, { ...meta, direction: "HTTP request + response", ...r });
    written.push({ file: `rest/${r.name}.json`, kind: "rest", status: r.status });
}

// README
const lines = [];
lines.push(`# Uptime Kuma ${version} payload fixtures`);
lines.push("");
lines.push(`Recorded ${startedAt.toISOString()} from a local \`louislam/uptime-kuma:${version}\` (see \`infra/docker-compose.dev.yml\`)`);
lines.push(`by \`infra/kuma-seed/record.mjs ${process.argv.slice(2).join(" ")}\` after \`setup.mjs\` and \`seed.mjs\`.`);
lines.push(`Kuma version string from the \`info\` event: \`${version}\`. Server timezone: \`${live.info?.serverTimezone}\` (${live.info?.serverTimezoneOffset}).`);
lines.push("");
lines.push("Secrets are redacted: JWTs become `REDACTED_JWT`, values under keys matching");
lines.push("`token|password|secret|api_key|accesstoken` become `REDACTED(len=N)`, otpauth URIs lose their secret.");
lines.push("");
lines.push("## Layout");
lines.push("");
lines.push("- `<event>.json` - server->client Socket.IO events. `samples[].args` are the positional emit arguments.");
lines.push(`  At most ${PER_KEY} samples per bucket (\`sampleKeys\`), e.g. one bucket per heartbeat status / monitor id / uptime period.`);
lines.push("- `ack/<event>[.<variant>].json` - client->server emits with the callback (`response`) the server returned.");
lines.push("- `rest/<name>.json` - plain HTTP responses from the public REST endpoints (and `/metrics` with basic auth).");
lines.push("");
lines.push("## Server->client events");
lines.push("");
lines.push("| file | samples | total received | buckets |");
lines.push("|---|---:|---:|---|");
for (const w of written.filter((w) => w.kind === "event")) {
    const buckets = [...events.get(w.file.replace(/\.json$/, "")).keys()];
    lines.push(`| \`${w.file}\` | ${w.count} | ${w.total} | ${buckets.slice(0, 8).join(", ")}${buckets.length > 8 ? ", ..." : ""} |`);
}
lines.push("");
lines.push("## Ack (callback) fixtures");
lines.push("");
lines.push("| file | event | ok |");
lines.push("|---|---|---|");
for (const w of written.filter((w) => w.kind === "ack")) {
    lines.push(`| \`${w.file}\` | \`${w.event}\` | ${w.ok ?? "-"} |`);
}
lines.push("");
lines.push("## REST fixtures");
lines.push("");
lines.push("| file | status |");
lines.push("|---|---:|");
for (const w of written.filter((w) => w.kind === "rest")) {
    lines.push(`| \`${w.file}\` | ${w.status} |`);
}
lines.push("");
lines.push("## 2FA");
lines.push("");
lines.push(twofaNote + ".");
lines.push("");
lines.push("## Seeded monitors at recording time");
lines.push("");
lines.push("| id | type | name | parent | tags |");
lines.push("|---:|---|---|---:|---|");
for (const m of monitors) {
    lines.push(`| ${m.id} | ${m.type} | ${m.name} | ${m.parent ?? ""} | ${(m.tags ?? []).map((t) => `${t.name}:${t.value}`).join(", ")} |`);
}
lines.push("");
fs.writeFileSync(path.join(outDir, "README.md"), lines.join("\n"));

log(`wrote ${written.length} fixture files + README.md to ${outDir}`);
