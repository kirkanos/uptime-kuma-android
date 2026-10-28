#!/usr/bin/env node
// Seed a local Uptime Kuma 2.5.x with a representative set of monitors, tags,
// notifications, a maintenance window and a status page.
//
//   node seed.mjs [--interval 20]
//
// Idempotent-ish: monitors / tags / notifications / status page are looked up by
// name (or slug) and skipped when they already exist. Re-running on a seeded
// instance therefore only fills gaps.

import {
    connectAndLogin,
    ack,
    waitFor,
    log,
    parseArgs,
    genSecret,
    formatDateTimeInTz,
    sleep,
} from "./lib.mjs";

const args = parseArgs();
const INTERVAL = Number(args.interval ?? 20); // Kuma's minimum is 20 s
const STATUS_PAGE_SLUG = "dev";

const { socket, state } = await connectAndLogin();
const serverTz = state.info?.serverTimezone ?? "UTC";
log(`logged in; Kuma ${state.info?.version} (tz ${serverTz}); ${Object.keys(state.monitorList ?? {}).length} monitors exist`);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Frontend defaults for a new monitor (src/pages/EditMonitor.vue monitorDefaults, 2.5.8). */
function baseMonitor(overrides = {}) {
    return {
        type: "http",
        name: "",
        parent: null,
        url: "https://",
        wsSubprotocol: "",
        method: "GET",
        protocol: null,
        location: "world",
        ipFamily: null,
        interval: INTERVAL,
        retryInterval: INTERVAL,
        resendInterval: 0,
        maxretries: 0,
        retryOnlyOnStatusCodeFailure: false,
        timeout: Math.floor(INTERVAL * 0.8),
        notificationIDList: {},
        ignoreTls: false,
        upsideDown: false,
        expiryNotification: false,
        domainExpiryNotification: true,
        maxredirects: 10,
        accepted_statuscodes: ["200-299"],
        saveResponse: false,
        saveErrorResponse: true,
        responseMaxLength: 1024,
        dns_resolve_type: "A",
        dns_resolve_server: "1.1.1.1",
        docker_container: "",
        docker_host: null,
        proxyId: null,
        basic_auth_user: "",
        basic_auth_pass: "",
        bearer_token: "",
        mqttUsername: "",
        mqttPassword: "",
        mqttTopic: "",
        mqttWebsocketPath: "",
        mqttSuccessMessage: "",
        mqttCheckType: "keyword",
        authMethod: null,
        oauth_auth_method: "client_secret_basic",
        httpBodyEncoding: "json",
        kafkaProducerBrokers: [],
        kafkaProducerSaslOptions: { mechanism: "None" },
        cacheBust: false,
        kafkaProducerSsl: false,
        kafkaProducerAllowAutoTopicCreation: false,
        gamedigGivenPortOnly: true,
        gamedigToken: "",
        remote_browser: null,
        screenshot_delay: 0,
        rabbitmqNodes: [],
        rabbitmqUsername: "",
        rabbitmqPassword: "",
        conditions: [],
        system_service_name: "",
        sshAuthMethod: "password",
        ntpStratumThreshold: 5,
        ntpTimeOffsetThreshold: 1000,
        ntpRootDispersionThreshold: 500,
        // ping defaults
        ping_count: 3,
        ping_numeric: true,
        packetSize: 56,
        ping_per_request_timeout: 2,
        ...overrides,
    };
}

function findMonitorByName(name) {
    return Object.values(state.monitorList ?? {}).find((m) => m.name === name) ?? null;
}

async function refreshMonitorList() {
    const p = waitFor(socket, "monitorList");
    await ack(socket, "getMonitorList");
    const [list] = await p;
    state.monitorList = list;
}

/** Create a monitor unless one with the same name exists. Returns its id. */
async function ensureMonitor(spec) {
    const existing = findMonitorByName(spec.name);
    if (existing) {
        log(`= monitor "${spec.name}" exists (id ${existing.id})`);
        return existing.id;
    }
    const res = await ack(socket, "add", baseMonitor(spec));
    if (!res?.ok) {
        throw new Error(`add "${spec.name}" failed: ${res?.msg}`);
    }
    log(`+ monitor "${spec.name}" (id ${res.monitorID}, type ${spec.type})`);
    return res.monitorID;
}

async function ensureTag(name, color) {
    const { tags } = await ack(socket, "getTags");
    const existing = tags.find((t) => t.name === name);
    if (existing) {
        return existing;
    }
    const res = await ack(socket, "addTag", { name, color });
    if (!res?.ok) {
        throw new Error(`addTag ${name} failed: ${res?.msg}`);
    }
    log(`+ tag "${name}" (id ${res.tag.id})`);
    return res.tag;
}

async function ensureNotification(spec) {
    const existing = (state.notificationList ?? []).find((n) => n.name === spec.name);
    if (existing) {
        log(`= notification "${spec.name}" exists (id ${existing.id})`);
        return existing.id;
    }
    const p = waitFor(socket, "notificationList");
    const res = await ack(socket, "addNotification", spec, null);
    if (!res?.ok) {
        throw new Error(`addNotification ${spec.name} failed: ${res?.msg}`);
    }
    [state.notificationList] = await p;
    log(`+ notification "${spec.name}" (type ${spec.type}, id ${res.id})`);
    return res.id;
}

// ---------------------------------------------------------------------------
// Notifications (shapes only; ntfy is deny-all so sends will 403 harmlessly)
// ---------------------------------------------------------------------------

const webhookNotificationId = await ensureNotification({
    name: "Dev webhook",
    type: "webhook",
    isDefault: false,
    applyExisting: false,
    webhookURL: "http://ntfy:80/upTESTTOPIC-webhook",
    webhookContentType: "json",
    webhookCustomBody: "",
    webhookAdditionalHeaders: "",
    httpMethod: "POST",
});

const ntfyNotificationId = await ensureNotification({
    name: "Dev ntfy",
    type: "ntfy",
    isDefault: false,
    applyExisting: false,
    ntfyserverurl: "http://ntfy:80",
    ntfytopic: "upTESTTOPIC",
    ntfyPriority: 5,
    ntfyPriorityDown: 5,
    ntfyAuthenticationMethod: "none",
    ntfyusername: "",
    ntfypassword: "",
    ntfyaccesstoken: "",
    ntfyIcon: "",
    ntfyUseTemplate: false,
    ntfyCall: false,
});

// ---------------------------------------------------------------------------
// Monitors
// ---------------------------------------------------------------------------

const httpId = await ensureMonitor({
    name: "HTTP example.com",
    type: "http",
    url: "https://example.com",
    expiryNotification: true, // makes Kuma store TLS info -> certInfo event
});

const keywordId = await ensureMonitor({
    name: "HTTP keyword example.com",
    type: "keyword",
    url: "https://example.com",
    keyword: "Example Domain",
    invertKeyword: false,
});

await ensureMonitor({
    name: "JSON query Kuma entry-page",
    type: "json-query",
    url: "http://localhost:3001/api/entry-page", // the Kuma server itself, always reachable
    jsonPath: "$.type",
    jsonPathOperator: "==",
    expectedValue: "entryPage",
});

const tcpId = await ensureMonitor({
    name: "TCP example.com:443",
    type: "port",
    hostname: "example.com",
    port: 443,
});

await ensureMonitor({
    name: "Ping 1.1.1.1",
    type: "ping",
    hostname: "1.1.1.1",
    timeout: 10,
});

await ensureMonitor({
    name: "DNS example.com A",
    type: "dns",
    hostname: "example.com",
    dns_resolve_server: "1.1.1.1",
    dns_resolve_type: "A",
    port: 53,
});

await ensureMonitor({
    name: "Push demo",
    type: "push",
    pushToken: genSecret(32),
});

const taggedId = await ensureMonitor({
    name: "Tagged HTTP example.org",
    type: "http",
    url: "https://example.org",
});

const failingId = await ensureMonitor({
    name: "Failing HTTP localhost:9",
    type: "http",
    url: "http://localhost:9/",
    maxretries: 1,
    notificationIDList: { [ntfyNotificationId]: true, [webhookNotificationId]: true },
});

const maintId = await ensureMonitor({
    name: "Under maintenance HTTP",
    type: "http",
    url: "https://example.net",
});

const groupId = await ensureMonitor({
    name: "Group Web",
    type: "group",
});

await ensureMonitor({
    name: "Group child HTTP example.com",
    type: "http",
    url: "https://example.com/?child=1",
    parent: groupId,
});

await ensureMonitor({
    name: "Group child TCP example.com:80",
    type: "port",
    hostname: "example.com",
    port: 80,
    parent: groupId,
});

await refreshMonitorList();

// ---------------------------------------------------------------------------
// Tags on "Tagged HTTP example.org"
// ---------------------------------------------------------------------------

const tagged = state.monitorList[taggedId];
const wanted = [
    { name: "env", color: "#059669", value: "dev" },
    { name: "team", color: "#7C3AED", value: "android" },
];
for (const w of wanted) {
    const tag = await ensureTag(w.name, w.color);
    const already = (tagged?.tags ?? []).some((t) => t.tag_id === tag.id && t.value === w.value);
    if (already) {
        log(`= tag ${w.name}:${w.value} already on monitor ${taggedId}`);
        continue;
    }
    const res = await ack(socket, "addMonitorTag", tag.id, taggedId, w.value);
    if (!res?.ok) {
        throw new Error(`addMonitorTag failed: ${res?.msg}`);
    }
    log(`+ tag ${w.name}:${w.value} -> monitor ${taggedId}`);
}

// ---------------------------------------------------------------------------
// Status page "dev" with one group of two monitors
// ---------------------------------------------------------------------------

let statusPage = await ack(socket, "getStatusPage", STATUS_PAGE_SLUG);
if (!statusPage?.ok) {
    const res = await ack(socket, "addStatusPage", "Dev Status", STATUS_PAGE_SLUG);
    if (!res?.ok) {
        throw new Error(`addStatusPage failed: ${res?.msg}`);
    }
    log(`+ status page /status/${STATUS_PAGE_SLUG}`);
    statusPage = await ack(socket, "getStatusPage", STATUS_PAGE_SLUG);
    const config = {
        ...statusPage.config,
        description: "Local dev status page used for Android client fixtures.",
        published: true,
        showTags: true,
        showPoweredBy: true,
        showCertificateExpiry: true,
        footerText: "kuma-seed fixtures",
        domainNameList: [],
    };
    const publicGroupList = [
        {
            name: "Core",
            monitorList: [{ id: httpId }, { id: tcpId }],
        },
    ];
    const saved = await ack(socket, "saveStatusPage", STATUS_PAGE_SLUG, config, "/icon.svg", publicGroupList);
    if (!saved?.ok) {
        throw new Error(`saveStatusPage failed: ${saved?.msg}`);
    }
    log(`~ status page saved with group "Core" (${publicGroupList[0].monitorList.length} monitors)`);
} else {
    log(`= status page /status/${STATUS_PAGE_SLUG} exists`);
}

// ---------------------------------------------------------------------------
// Maintenance window (strategy "single", active right now for ~3 h) on one monitor
// ---------------------------------------------------------------------------

const maintenanceTitle = "Dev maintenance (single)";
const maintenanceList = Object.values(state.maintenanceList ?? {});
let maintenance = maintenanceList.find((m) => m.title === maintenanceTitle);
if (!maintenance) {
    const now = Date.now();
    const payload = {
        title: maintenanceTitle,
        description: "Seeded by kuma-seed; covers 'Under maintenance HTTP' so status-3 heartbeats exist.",
        strategy: "single",
        active: 1,
        cron: "30 3 * * *",
        durationMinutes: 60,
        intervalDay: 1,
        dateRange: [
            formatDateTimeInTz(new Date(now - 5 * 60_000), serverTz),
            formatDateTimeInTz(new Date(now + 3 * 3_600_000), serverTz),
        ],
        timeRange: [
            { hours: 2, minutes: 0 },
            { hours: 3, minutes: 0 },
        ],
        weekdays: [],
        daysOfMonth: [],
        timezoneOption: "SAME_AS_SERVER",
    };
    const res = await ack(socket, "addMaintenance", payload);
    if (!res?.ok) {
        throw new Error(`addMaintenance failed: ${res?.msg}`);
    }
    log(`+ maintenance "${maintenanceTitle}" (id ${res.maintenanceID}) ${payload.dateRange.join(" -> ")} ${serverTz}`);
    const m1 = await ack(socket, "addMonitorMaintenance", res.maintenanceID, [{ id: maintId }]);
    const m2 = await ack(socket, "addMaintenanceStatusPage", res.maintenanceID, [{ id: statusPage.config.id }]);
    log(`~ maintenance linked: monitor ${m1?.ok ? "ok" : m1?.msg}, status page ${m2?.ok ? "ok" : m2?.msg}`);
} else {
    log(`= maintenance "${maintenanceTitle}" exists (id ${maintenance.id})`);
}

await sleep(500);
await refreshMonitorList();
log(`done: ${Object.keys(state.monitorList).length} monitors total`);
for (const m of Object.values(state.monitorList)) {
    log(`   #${m.id} ${m.type.padEnd(10)} ${m.name}${m.parent ? ` (parent ${m.parent})` : ""}${m.tags?.length ? ` tags=${m.tags.map((t) => `${t.name}:${t.value}`).join(",")}` : ""}`);
}
socket.disconnect();
