const el = (id) => document.getElementById(id);
const ago = (ts) => {
  if (!ts) return "never";
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return `${Math.round(s / 3600)}h ago`;
};

async function render() {
  const { status = {} } = await chrome.storage.local.get("status");
  const { spaceId } = await chrome.storage.sync.get("spaceId");
  const { deviceName } = await chrome.storage.local.get("deviceName");
  el("count").textContent = status.count ?? "—";
  el("device").textContent = spaceId
    ? `tabs synced from ${deviceName || "this device"}`
    : "not configured — open Settings";
  el("sync").textContent = `last sync ${ago(status.lastSync)}`;
  el("closed").textContent = status.lastClosedCount
    ? `closed ${status.lastClosedCount} from phone ${ago(status.lastClosed)}`
    : "";
  el("err").textContent = status.error || "";
}

el("now").addEventListener("click", async () => {
  el("now").disabled = true;
  await chrome.runtime.sendMessage("sync-now");
  el("now").disabled = false;
  render();
});
el("opts").addEventListener("click", (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
});

render();
