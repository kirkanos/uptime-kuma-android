// Shared helpers for the kuma-seed scripts (setup.mjs, seed.mjs, record.mjs).
//
// Everything here talks to a *local dev* Uptime Kuma over Socket.IO. The default
// credentials are the throwaway dev defaults documented in infra/README.md.

import { io } from "socket.io-client";

export const KUMA_URL = normalizeUrl(process.env.KUMA_URL ?? "http://localhost:3001");
export const KUMA_USER = process.env.KUMA_USER ?? "admin";
export const KUMA_PASSWORD = process.env.KUMA_PASSWORD ?? "devpassword123!";
export const ACK_TIMEOUT_MS = Number(process.env.KUMA_ACK_TIMEOUT_MS ?? 30_000);

/** Accept ws://, wss://, http:// or https:// and hand socket.io an http(s) origin. */
function normalizeUrl(url) {
    return url.replace(/^ws:\/\//, "http://").replace(/^wss:\/\//, "https://").replace(/\/+$/, "");
}

/** Minimal CLI flag parser: --key value / --key=value / --flag. */
export function parseArgs(argv = process.argv.slice(2)) {
    const out = { _: [] };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (!a.startsWith("--")) {
            out._.push(a);
            continue;
        }
        const eq = a.indexOf("=");
        if (eq !== -1) {
            out[a.slice(2, eq)] = a.slice(eq + 1);
        } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
            out[a.slice(2)] = argv[++i];
        } else {
            out[a.slice(2)] = true;
        }
    }
    return out;
}

export function log(...args) {
    const ts = new Date().toISOString().slice(11, 23);
    console.error(`[${ts}]`, ...args);
}

/**
 * Open a Socket.IO connection to Kuma. Resolves once connected.
 * `onAny` (optional) is attached *before* connecting so the very first
 * server->client events (`info`, `setup`, `loginRequired`) are not missed.
 */
export function connect({ url = KUMA_URL, onAny } = {}) {
    return new Promise((resolve, reject) => {
        const socket = io(url, {
            path: "/socket.io",
            transports: ["websocket"],
            reconnection: false,
            timeout: 10_000,
        });
        if (onAny) {
            socket.onAny(onAny);
        }
        socket.once("connect", () => resolve(socket));
        socket.once("connect_error", (err) => reject(new Error(`connect_error to ${url}: ${err.message}`)));
    });
}

/** Emit an event that expects a callback and resolve with the callback payload. */
export function ack(socket, event, ...args) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`ack timeout for "${event}"`)), ACK_TIMEOUT_MS);
        socket.emit(event, ...args, (...res) => {
            clearTimeout(timer);
            resolve(res.length <= 1 ? res[0] : res);
        });
    });
}

/**
 * Wait until the server has finished its `connection` handler. Kuma 2.5.x awaits
 * `sendInfo()` (DB reads) *before* it registers `socket.on("login", ...)` etc., so an
 * emit sent right after `connect` can be silently dropped. The handler ends by emitting
 * `loginRequired` (or `autoLogin` when auth is disabled); `setup` is emitted earlier on
 * a fresh instance but the handlers are registered afterwards, so we still wait for the
 * final event. Clients should mirror this: do not emit `login` before `loginRequired`.
 */
export function waitForServerReady(socket, timeoutMs = 15_000) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("timeout waiting for loginRequired/autoLogin")), timeoutMs);
        const done = (event) => {
            clearTimeout(timer);
            socket.off("loginRequired", onLoginRequired);
            socket.off("autoLogin", onAutoLogin);
            resolve(event);
        };
        const onLoginRequired = () => done("loginRequired");
        const onAutoLogin = () => done("autoLogin");
        socket.on("loginRequired", onLoginRequired);
        socket.on("autoLogin", onAutoLogin);
    });
}

/** Wait for the next `event` whose args satisfy `predicate` (default: any). */
export function waitFor(socket, event, predicate = () => true, timeoutMs = ACK_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            socket.off(event, handler);
            reject(new Error(`timeout waiting for "${event}"`));
        }, timeoutMs);
        function handler(...args) {
            if (predicate(...args)) {
                clearTimeout(timer);
                socket.off(event, handler);
                resolve(args);
            }
        }
        socket.on(event, handler);
    });
}

/**
 * Log in with username/password. Returns the full ack ({ ok, token } or
 * { tokenRequired: true } when 2FA is enabled, in which case `totp` is used).
 */
export async function login(socket, { username = KUMA_USER, password = KUMA_PASSWORD, totp } = {}) {
    const payload = { username, password };
    if (totp) {
        payload.token = totp;
    }
    const res = await ack(socket, "login", payload);
    if (!res?.ok) {
        const why = res?.tokenRequired ? "2FA token required" : res?.msg ?? JSON.stringify(res);
        throw new Error(`login failed: ${why}`);
    }
    return res;
}

/**
 * Connect + log in, collecting the "afterLogin" burst (monitorList, notificationList,
 * maintenanceList, statusPageList, info ...) into `state` so callers can inspect
 * current server state without extra round trips.
 */
export async function connectAndLogin(opts = {}) {
    const state = {
        info: null,
        monitorList: null,
        notificationList: null,
        maintenanceList: null,
        statusPageList: null,
        proxyList: null,
        monitorTypeList: null,
    };
    const socket = await connect({
        ...opts,
        onAny: (event, ...args) => {
            if (event in state && !(event === "info" && !args[0]?.version && state.info?.version)) {
                state[event] = args[0];
            }
            // Mimic the browser: answer the one-time timezone request.
            if (event === "initServerTimezone") {
                socket.emit("initServerTimezone", process.env.KUMA_SERVER_TZ ?? "Europe/Berlin");
            }
            opts.onAny?.(event, ...args);
        },
    });
    await waitForServerReady(socket);
    const loginRes = await login(socket, opts);
    // monitorList is emitted before the login ack; the other lists are emitted
    // via Promise.allSettled before the ack as well, but give stragglers a beat.
    if (!state.monitorList) {
        await waitFor(socket, "monitorList", () => true, 10_000).catch(() => {});
    }
    await sleep(300);
    return { socket, state, token: loginRes.token };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Random alphanumeric secret, same alphabet/length as Kuma's genSecret(). */
export function genSecret(length = 64) {
    const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let out = "";
    for (let i = 0; i < length; i++) {
        out += chars[Math.floor(Math.random() * chars.length)];
    }
    return out;
}

/** Format a Date as Kuma's maintenance "YYYY-MM-DD HH:mm:ss" in a given IANA timezone. */
export function formatDateTimeInTz(date, timeZone) {
    const parts = Object.fromEntries(
        new Intl.DateTimeFormat("en-GB", {
            timeZone,
            hourCycle: "h23",
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
        })
            .formatToParts(date)
            .map((p) => [p.type, p.value])
    );
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}
