# Tabs — Chrome bridge (v0)

Mirrors this browser's open tabs into a Firebase Realtime Database, and closes
tabs when the phone asks. No Firebase SDK — plain HTTPS against the REST API.

## Install

1. `chrome://extensions` → enable **Developer mode**
2. **Load unpacked** → select this `extension/` folder
3. Click the extension icon → **Settings**
   - paste the Realtime Database URL
   - click **Generate** for a Space ID, then save it somewhere (the phone needs it)
   - name this device
4. **Save.** The popup should show a tab count within a second or two.

## Which tabs this sees

Only the browsers this extension is actually loaded into. Chrome's own "tabs
from other devices" list is closed to third-party extensions -- there is no API
to read it and none to close those tabs -- and mobile Chrome has no extensions
at all. So:

| Where the tab is | Synced | Closable from the phone |
|---|---|---|
| A desktop Chrome with this extension loaded | yes | yes |
| A desktop Chrome without it | no | no |
| Chrome on Android or iOS | no | no |

Adding a machine: load the unpacked folder there too and reuse the *same* Space
ID. `dbUrl` and `spaceId` live in `chrome.storage.sync`, so they are usually
already filled in; the device **name** is deliberately local, so each machine
labels its own cards. Unpacked extensions are not themselves synced by Chrome,
so the folder has to exist on each machine.

## Database rules (paste into Realtime Database → Rules)

```json
{
  "rules": {
    "spaces": {
      "$spaceId": {
        ".read": "$spaceId.length >= 24",
        ".write": "$spaceId.length >= 24"
      }
    }
  }
}
```

This is a capability-URL posture: the Space ID *is* the credential. Reads at
`/spaces` are denied, so the space cannot be enumerated — but anyone who learns
the ID can read your tab list. Fine for v0; v1 should move to anonymous Firebase
Auth with uid-scoped rules.

## Data shape

```
spaces/<spaceId>/
  devices/<deviceId>/
    name, lastSync
    tabs/<urlHash>/   url, title, favIconUrl, firstSeen, updatedAt
  commands/<deviceId>/<pushId>/   { type: "close", url }
  archive/<urlHash>/              url, title, closedAt, firstSeen
```

- **`urlHash`** is the first 10 bytes of SHA-256 of the URL. Tab ids aren't
  stable across restarts; URLs are. Same page in two windows = one entry, and
  closing it closes both copies.
- **`firstSeen`** is kept in `chrome.storage.local`, so tab age survives
  restarts and is real data from day one.
- **Pinned tabs and non-`http(s)` tabs are never synced** — pinned tabs are
  tools, not reading.
- The extension only *executes* close commands. The phone is responsible for
  writing the `archive` entry before it writes the command, so swiping left can
  never lose a URL.

## Timing

A 1-minute `chrome.alarms` tick is the guarantee; tab events also trigger an
opportunistic push ~2s later. So expect a desktop tab to disappear up to about
a minute after you swipe. MV3 tears the service worker down between ticks —
that's normal, not a bug.
