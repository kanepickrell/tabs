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

The extension also writes **briefs** — a triage line, key points and a couple of
questions worth raising — using the Claude API. Optional: with no key set,
nothing is ever sent to Anthropic and everything else works unchanged.

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

## Briefs

Set an Anthropic API key in the extension's settings and each new tab gets a
brief: one triage sentence, three to five concrete key points, a question or
two worth raising with someone, and an estimated reading time. They appear on
the card, so the decision to read or close is made with something better than
a title.

Why the extension does this rather than the phone:

- It is inside your logged-in session, so it reads paywalled and authenticated
  pages the phone could never fetch.
- The key stays in `chrome.storage.local` on your own machine instead of being
  compiled into an APK. It is never synced, never written to the database and
  never sent to the phone.

Briefs are keyed by the same URL hash as tabs and stored outside the per-device
tree, so a page briefed on one machine is already briefed on every other.
Two per minute, so a fresh install does not fire twenty requests at once.

Model is `claude-opus-5` with `claude-opus-4-8` as a refusal fallback, at
medium effort — roughly $0.04-0.05 per page at ~6k input tokens. Both the model
and the effort are constants at the top of `extension/brief.js`.

**This needs broad host access.** Reading article text means `chrome.scripting`
has to run in the page, which requires permission for all sites. Chrome will
ask you to approve that on reload.

## Not built yet

Swipe-right "keep" only lasts the session; it should resurface the tab in a few
days. No time budget filter ("I have 7 minutes"), no daily cap, no TTS, no
AI topic clustering beyond grouping by hostname, and no share-sheet intake —
which is the only possible route for tabs opened on the phone itself.

## Security posture

The Space ID *is* the credential. Reads at `/spaces` are denied by the rules, so
the space can't be enumerated — but anyone who learns the ID can read your tab
list. Acceptable for a single-user v0; v1 should move to anonymous Firebase Auth
with uid-scoped rules before this holds anything you'd mind leaking.
