// Tabs — v0 bridge service worker.
//
// Two jobs, both driven by a 1-minute alarm (plus opportunistic tab events):
//   push  — mirror this browser's open tabs into the shared JSON store
//   poll  — look for "close this URL" commands from the phone and execute them
//
// Tab ids are not stable across browser restarts, so every tab is keyed by a
// hash of its URL. That also dedupes the same page open in several windows,
// and lets the phone's close command find the tab again later.

const ALARM_NAME = "tabs-sync";
const PERIOD_MINUTES = 1;

// ---------------------------------------------------------------- config ----

async function getConfig() {
  // Only the database and the space are shared across machines. Anything that
  // identifies THIS browser has to stay local, or every machine ends up
  // claiming the same name and every card is mislabelled.
  const shared = await chrome.storage.sync.get(["dbUrl", "spaceId"]);
  if (!shared.dbUrl || !shared.spaceId) return null;

  const local = await chrome.storage.local.get(["deviceId", "deviceName"]);
  let deviceId = local.deviceId;
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    await chrome.storage.local.set({ deviceId });
  }

  return {
    base: shared.dbUrl.replace(/\/+$/, ""),
    spaceId: shared.spaceId,
    deviceId,
    deviceName: local.deviceName || "desktop",
  };
}

// deviceName used to be stored in chrome.storage.sync, which meant a second
// machine inherited the first one's name. Move any such value down to local
// storage once, then clear it so it stops propagating.
async function migrateDeviceName() {
  const { deviceName } = await chrome.storage.sync.get("deviceName");
  if (!deviceName) return;
  const local = await chrome.storage.local.get("deviceName");
  if (!local.deviceName) await chrome.storage.local.set({ deviceName });
  await chrome.storage.sync.remove("deviceName");
}

function path(cfg, ...parts) {
  return `${cfg.base}/spaces/${cfg.spaceId}/${parts.join("/")}.json`;
}

async function setStatus(patch) {
  const { status = {} } = await chrome.storage.local.get("status");
  await chrome.storage.local.set({ status: { ...status, ...patch } });
}

// ------------------------------------------------------------ collecting ----

// chrome://, extension pages, about:blank and the like are never reading.
function isReadable(url) {
  return /^https?:/i.test(url);
}

async function hashUrl(url) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
  return [...new Uint8Array(digest)]
    .slice(0, 10)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

async function collectTabs() {
  const tabs = await chrome.tabs.query({ windowType: "normal" });
  const { seen = {} } = await chrome.storage.local.get("seen");
  const now = Date.now();
  const out = {};
  let seenChanged = false;

  for (const tab of tabs) {
    if (!tab.url || !isReadable(tab.url)) continue;
    if (tab.pinned) continue; // pinned tabs are tools, not reading

    const key = await hashUrl(tab.url);
    if (!seen[key]) {
      seen[key] = now;
      seenChanged = true;
    }
    out[key] = {
      url: tab.url,
      title: tab.title || tab.url,
      favIconUrl: tab.favIconUrl || null,
      firstSeen: seen[key], // survives restarts, so tab age is real
      updatedAt: now,
    };
  }

  if (seenChanged) await chrome.storage.local.set({ seen });
  return out;
}

// --------------------------------------------------------------- pushing ----

async function pushTabs() {
  const cfg = await getConfig();
  if (!cfg) return setStatus({ error: "Not configured yet — open the options page." });

  try {
    const tabs = await collectTabs();
    const res = await fetch(path(cfg, "devices", cfg.deviceId), {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: cfg.deviceName, lastSync: Date.now(), tabs }),
    });
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
    await setStatus({ error: null, lastSync: Date.now(), count: Object.keys(tabs).length });
  } catch (err) {
    await setStatus({ error: `Push failed: ${err.message}` });
  }
}

// --------------------------------------------------------------- closing ----

async function pollCommands() {
  const cfg = await getConfig();
  if (!cfg) return;

  let commands;
  try {
    const res = await fetch(path(cfg, "commands", cfg.deviceId));
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
    commands = await res.json();
  } catch (err) {
    return setStatus({ error: `Poll failed: ${err.message}` });
  }
  if (!commands) return;

  let closed = 0;
  for (const [id, cmd] of Object.entries(commands)) {
    if (cmd && cmd.type === "close" && cmd.url) {
      // Not chrome.tabs.query({url}): that takes match patterns, which cannot
      // express a query string, so most real article URLs would never match.
      const all = await chrome.tabs.query({ windowType: "normal" });
      const victims = all.filter((t) => t.url === cmd.url).map((t) => t.id);
      if (victims.length) {
        try {
          await chrome.tabs.remove(victims);
          closed += victims.length;
        } catch (err) {
          await setStatus({ error: `Close failed: ${err.message}` });
          continue; // leave the command in place to retry next tick
        }
      }
      // Already gone (closed by hand) counts as done — fall through to delete.
    }
    await fetch(path(cfg, "commands", cfg.deviceId, id), { method: "DELETE" });
  }

  if (closed) {
    await setStatus({ lastClosed: Date.now(), lastClosedCount: closed });
    await pushTabs(); // reflect the new state immediately
  }
}

async function tick() {
  await pushTabs();
  await pollCommands();
}

// --------------------------------------------------------------- wiring -----

async function ensureAlarm() {
  const existing = await chrome.alarms.get(ALARM_NAME);
  if (!existing) {
    chrome.alarms.create(ALARM_NAME, { periodInMinutes: PERIOD_MINUTES, when: Date.now() + 1000 });
  }
}

async function start() {
  await migrateDeviceName();
  await ensureAlarm();
  await tick();
}

chrome.runtime.onInstalled.addListener(start);
chrome.runtime.onStartup.addListener(start);
chrome.alarms.onAlarm.addListener((alarm) => { if (alarm.name === ALARM_NAME) tick(); });

// Opportunistic: push sooner than the next alarm when tabs actually change.
// Best effort only — the service worker may be torn down before it fires.
let debounce = null;
function scheduleSync() {
  clearTimeout(debounce);
  debounce = setTimeout(pushTabs, 2000);
}
chrome.tabs.onCreated.addListener(scheduleSync);
chrome.tabs.onRemoved.addListener(scheduleSync);
chrome.tabs.onUpdated.addListener((_id, changeInfo) => {
  if (changeInfo.status === "complete" || changeInfo.title || changeInfo.url) scheduleSync();
});

chrome.runtime.onMessage.addListener((msg, _sender, respond) => {
  if (msg === "sync-now") {
    tick().then(() => respond({ ok: true }));
    return true; // async response
  }
});
