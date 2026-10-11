# Tabs

Read your open Chrome tabs on your phone, and swipe left to close them on the
desktop when you're done. Something to keep up with instead of social media.

v0 proves the hard part only: the round trip from desktop to phone and back.
No summaries, no AI, no cards.

## Pieces

| | |
|---|---|
| `extension/` | Private unpacked Chrome extension. Lists tabs, executes close commands. |
| `android/` | Compose app. Swipe left closes the tab on the desktop. |
| `tools/phone.mjs` | CLI stand-in for the phone, for testing before the app is installed. |

There is no server. Firebase Realtime Database is used as a dumb JSON store
over plain HTTPS — no Firebase SDK on either side, no `google-services.json`,
no Gradle plugin.

## Setup

**1. Firebase** — console.firebase.google.com → new project (skip Analytics) →
Build → Realtime Database → Create → **test mode**. Copy the database URL.
Then paste the rules from `extension/README.md` into the Rules tab.

**2. Extension** — `chrome://extensions` → Developer mode → Load unpacked →
pick `extension/`. Open its Settings, paste the database URL, click **Generate**
for a Space ID, name the device, Save. The popup should show a tab count.

**3. Verify without the phone:**

```bash
export TABS_DB_URL=https://your-db.firebaseio.com
export TABS_SPACE_ID=the-generated-space-id
node tools/phone.mjs list
node tools/phone.mjs close <urlHash>     # tab vanishes on the desktop within ~1 min
node tools/phone.mjs archive             # nothing was lost
```

**4. Android** — put the same two values in `android/local.properties`:

```properties
TABS_DB_URL=https://your-db.firebaseio.com
TABS_SPACE_ID=the-generated-space-id
sdk.dir=C:/Users/kpickrel/AppData/Local/Android/Sdk
```

They're compiled into `BuildConfig` because typing a 32-character secret on a
phone keyboard is miserable. Then:

```bash
export JAVA_HOME="/c/Users/kpickrel/tools/jdk-17.0.20.1+1"
export ANDROID_HOME="/c/Users/kpickrel/AppData/Local/Android/Sdk"
cd android && ./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## How it behaves

- Tabs are sorted **oldest first** — the oldest tab is the tab debt, and it's
  usually the easiest to close unread.
- **Tap** a card to open it in the browser. **Swipe left** to close the tab on
  the desktop; **swipe right** to keep it and take it off today's pile.
- Swiping left **archives the URL first**, then queues the close. Nothing a
  swipe touches is ever unrecoverable; that fear is the whole reason tabs pile
  up in the first place.
- Pinned tabs are never synced. They're tools, not reading.
- Only browsers running the extension are seen — Chrome's synced-tab list is
  closed to extensions, and mobile Chrome has none. See `extension/README.md`.
- The deck can be ordered oldest-first (tab debt), freshest-first, or grouped by
  site so one topic's tabs arrive as a single run.
- The desktop catches up within about a minute (MV3 alarm floor), not instantly.

## Not built yet

Swipe-right "keep" only lasts the session; it should resurface the tab in a few
days. No page-text extraction, no summaries, no reading-time estimate, no time
budget filter, no daily cap, no conversation prompts, no TTS, no share-sheet
intake (the only route for tabs opened on the phone itself). All of it layers on
top of this loop.

## Security posture

The Space ID *is* the credential. Reads at `/spaces` are denied by the rules, so
the space can't be enumerated — but anyone who learns the ID can read your tab
list. Acceptable for a single-user v0; v1 should move to anonymous Firebase Auth
with uid-scoped rules before this holds anything you'd mind leaking.
