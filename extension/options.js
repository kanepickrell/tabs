// Shared across every machine signed into this Chrome profile.
const SHARED = ["dbUrl", "spaceId"];
// Per-machine: a second computer must not inherit this one's name.
const LOCAL = ["deviceName", "anthropicKey"];
// Read alongside LOCAL but handled separately: it is a checkbox, not a field.
const LOCAL_FLAGS = ["briefsEnabled"];
const el = (id) => document.getElementById(id);

Promise.all([chrome.storage.sync.get(SHARED), chrome.storage.local.get([...LOCAL, ...LOCAL_FLAGS])])
  .then(([shared, local]) => {
    for (const f of SHARED) el(f).value = shared[f] || "";
    for (const f of LOCAL) el(f).value = local[f] || "";
    el("briefsEnabled").checked = local.briefsEnabled !== false;
  });

el("gen").addEventListener("click", () => {
  el("spaceId").value = crypto.randomUUID().replace(/-/g, "");
});

el("save").addEventListener("click", async () => {
  const shared = {};
  for (const f of SHARED) shared[f] = el(f).value.trim();
  const local = {};
  for (const f of LOCAL) local[f] = el(f).value.trim();
  local.briefsEnabled = el("briefsEnabled").checked;
  await chrome.storage.sync.set(shared);
  await chrome.storage.local.set(local);
  el("saved").textContent = "Saved.";
  chrome.runtime.sendMessage("sync-now");
  setTimeout(() => (el("saved").textContent = ""), 2500);
});
