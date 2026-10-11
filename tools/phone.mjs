#!/usr/bin/env node
// Stand-in for the Android app, so the v0 round trip can be tested before the
// phone exists.
//
//   node tools/phone.mjs list
//   node tools/phone.mjs close <urlHash>
//   node tools/phone.mjs archive
//
// Config: TABS_DB_URL and TABS_SPACE_ID environment variables.

const base = (process.env.TABS_DB_URL || "").replace(/\/+$/, "");
const space = process.env.TABS_SPACE_ID || "";
if (!base || !space) {
  console.error("Set TABS_DB_URL and TABS_SPACE_ID first.");
  process.exit(1);
}

const url = (...p) => `${base}/spaces/${space}/${p.join("/")}.json`;

async function get(...p) {
  const res = await fetch(url(...p));
  if (!res.ok) throw new Error(`GET ${res.status} ${await res.text()}`);
  return res.json();
}

async function send(method, body, ...p) {
  const res = await fetch(url(...p), {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${res.status} ${await res.text()}`);
  return res.json().catch(() => null);
}

const days = (ts) => Math.floor((Date.now() - ts) / 86400000);

async function allTabs() {
  const devices = (await get("devices")) || {};
  return Object.entries(devices).flatMap(([deviceId, device]) =>
    Object.entries(device.tabs || {}).map(([hash, tab]) => ({ ...tab, hash, deviceId, deviceName: device.name }))
  );
}

const [cmd, arg] = process.argv.slice(2);

if (cmd === "list") {
  const tabs = await allTabs();
  tabs.sort((a, b) => a.firstSeen - b.firstSeen);
  for (const t of tabs) {
    console.log(`${t.hash}  ${String(days(t.firstSeen)).padStart(4)}d  [${t.deviceName}]  ${t.title.slice(0, 70)}`);
  }
  console.log(`\n${tabs.length} tabs`);
} else if (cmd === "close") {
  const tab = (await allTabs()).find((t) => t.hash === arg);
  if (!tab) { console.error(`No tab with hash ${arg}`); process.exit(1); }
  // Archive first: swiping left must never be able to lose a URL.
  await send("PUT", {
    url: tab.url, title: tab.title, firstSeen: tab.firstSeen, closedAt: Date.now(),
  }, "archive", tab.hash);
  await send("POST", { type: "close", url: tab.url }, "commands", tab.deviceId);
  console.log(`archived + queued close: ${tab.title}`);
  console.log("the extension closes it within ~1 minute");
} else if (cmd === "archive") {
  const archive = (await get("archive")) || {};
  for (const [hash, a] of Object.entries(archive)) console.log(`${hash}  ${a.title}`);
  console.log(`\n${Object.keys(archive).length} archived`);
} else {
  console.error("usage: phone.mjs list | close <urlHash> | archive");
  process.exit(1);
}
