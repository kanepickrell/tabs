// Page briefs: extract the article text, ask Claude for a triage line, key
// points and a few questions worth raising, and store the result.
//
// This runs in the extension rather than on the phone for two reasons. The
// extension sits inside your logged-in browser session, so it can read
// paywalled and authenticated pages the phone could never fetch; and the API
// key stays on your own machine instead of being baked into an APK.

import { Anthropic } from "./vendor/anthropic.bundle.js";
import { hashUrl } from "./hash.js";

const MODEL = "claude-opus-5";
const FALLBACK_MODEL = "claude-opus-4-8";

// Enough article for a good brief without paying for the whole of a long read.
const MAX_CHARS = 24000;
// Briefs are written a couple per alarm tick so a fresh install does not fire
// twenty requests at once.
const PER_TICK = 2;
const MIN_CHARS = 400;
const MIN_WORDS = 250;

// Pages that are consoles, inboxes and account screens, not reading. Briefing
// one sends your own dashboard to the API and then stores a description of it
// in a database whose only credential is the space id. The first run of this
// feature briefed an API-keys page, balance and all, which is exactly the
// mistake this list exists to stop.
const NEVER_BRIEF = [
  /(^|\.)console\./i,
  /(^|\.)dashboard\./i,
  /(^|\.)admin\./i,
  /(^|\.)accounts?\./i,
  /(^|\.)login\./i,
  /(^|\.)billing\./i,
  /(^|\.)mail\.google\.com$/i,
  /(^|\.)messages\.google\.com$/i,
  /(^|\.)outlook\./i,
  /(^|\.)slack\.com$/i,
  /(^|\.)web\.whatsapp\.com$/i,
  /(^|\.)anthropic\.com$/i,
  // Your own conversations with an assistant are not reading material, and
  // they are often about private work.
  /(^|\.)claude\.ai$/i,
  /(^|\.)chatgpt\.com$/i,
  /(^|\.)gemini\.google\.com$/i,
  /(^|\.)notion\.so$/i,
  /(^|\.)calendar\.google\.com$/i,
  /(^|\.)drive\.google\.com$/i,
  /(^|\.)docs\.google\.com$/i,
  /(^|\.)localhost$/i,
  /^127\.0\.0\.1$/,
  /^192\.168\./,
];

// Path fragments that mean "your account", wherever they appear.
const NEVER_BRIEF_PATHS =
  /\/(settings|account|billing|invoice|password|checkout|admin|api[-_]?keys?)(\/|$|\?)/i;

function isPrivatePage(rawUrl, extraHosts) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    return true;
  }
  const host = url.hostname;
  if (NEVER_BRIEF.some((pattern) => pattern.test(host))) return true;
  if (NEVER_BRIEF_PATHS.test(url.pathname)) return true;
  return extraHosts.some((h) => h && (host === h || host.endsWith(`.${h}`)));
}

const BRIEF_TOOL = {
  name: "save_brief",
  description: "Record the brief for this page.",
  strict: true,
  input_schema: {
    type: "object",
    properties: {
      one_liner: {
        type: "string",
        description:
          "One sentence on what this page actually is and who would want it. " +
          "This is a triage line to help decide whether to spend the time - " +
          "it is not a replacement for reading the page.",
      },
      key_points: {
        type: "array",
        items: { type: "string" },
        description:
          "Three to five concrete takeaways. Prefer specific claims, numbers, " +
          "names and findings over topic labels. Never write 'discusses X'.",
      },
      questions: {
        type: "array",
        items: { type: "string" },
        description:
          "Two or three genuinely interesting questions, tensions or angles " +
          "this raises - the sort of thing worth saying to another person who " +
          "knows the subject. Not comprehension questions, and not a quiz.",
      },
      reading_minutes: {
        type: "integer",
        description: "Rough minutes to read the full page at normal speed.",
      },
      is_worth_reading: {
        type: "boolean",
        description:
          "True if the full page rewards reading beyond this brief. False for " +
          "thin, duplicated, promotional or purely navigational pages.",
      },
    },
    required: [
      "one_liner",
      "key_points",
      "questions",
      "reading_minutes",
      "is_worth_reading",
    ],
    additionalProperties: false,
  },
};

const SYSTEM = `You write briefs for someone triaging their own open browser tabs, so they can
decide what to actually read instead of scrolling social media.

Be concrete and specific. A brief that could have been written from the title
alone is a failure. Prefer the real claim, number or finding over a description
of the topic. Never pad, never hedge, and never say the page "explores" or
"discusses" something.

The questions matter most. Give the kind of observation that makes someone
sound like they have actually thought about the subject - a tension in the
argument, a surprising implication, a comparison the page misses. Avoid
anything that reads like a reading-comprehension exercise.`;

/**
 * Pulls the readable text out of a page.
 *
 * Runs in the page, so it has to be self-contained. It deliberately walks the
 * live DOM rather than cloning it: innerText depends on layout, and on a
 * detached clone it comes back empty or wrong.
 */
function extractPageText() {
  const root =
    document.querySelector("article") ||
    document.querySelector("main") ||
    document.body;
  if (!root) return { text: "", title: document.title };

  const SKIP = "script,style,noscript,nav,header,footer,aside,form,button,select,svg";
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
      const parent = node.parentElement;
      if (!parent || parent.closest(SKIP)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });

  const parts = [];
  let node;
  while ((node = walker.nextNode())) parts.push(node.nodeValue.trim());
  const text = parts.join(" ").replace(/\s{2,}/g, " ").trim();

  // A console or inbox is mostly links and controls; an article is mostly
  // prose. Link density separates the two better than length alone.
  const linkChars = [...root.querySelectorAll("a")]
    .reduce((sum, a) => sum + (a.textContent || "").trim().length, 0);

  return {
    text,
    title: document.title,
    words: text ? text.split(/\s+/).length : 0,
    linkDensity: text.length ? linkChars / text.length : 1,
    declaredArticle:
      Boolean(document.querySelector("article")) ||
      document.querySelector('meta[property="og:type"]')?.content === "article",
  };
}

async function readPage(tabId) {
  const [injection] = await chrome.scripting.executeScript({
    target: { tabId },
    func: extractPageText,
  });
  return injection?.result ?? { text: "", title: "" };
}

async function askClaude(apiKey, { url, title, text }) {
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true });

  const response = await client.beta.messages.create({
    model: MODEL,
    max_tokens: 2000,
    // A policy decline would otherwise just stop; this re-runs the same
    // request on the fallback model inside the same call.
    betas: ["server-side-fallback-2026-06-01"],
    fallbacks: [{ model: FALLBACK_MODEL }],
    // A short structured extraction. Raise this if the questions feel shallow.
    output_config: { effort: "medium" },
    system: SYSTEM,
    tools: [BRIEF_TOOL],
    tool_choice: { type: "tool", name: BRIEF_TOOL.name },
    messages: [
      {
        role: "user",
        content: `URL: ${url}\nTitle: ${title}\n\n---\n\n${text.slice(0, MAX_CHARS)}`,
      },
    ],
  });

  if (response.stop_reason === "refusal") {
    throw new Error(`declined (${response.stop_details?.category ?? "unknown"})`);
  }

  const call = response.content.find((block) => block.type === "tool_use");
  if (!call) throw new Error("no brief returned");

  return {
    ...call.input,
    model: response.model ?? MODEL,
    createdAt: Date.now(),
  };
}

/**
 * Brief any open tabs that do not have one yet. Returns how many were written.
 *
 * @param cfg    resolved config from background.js
 * @param apiKey Anthropic key, read from local storage by the caller
 * @param path   builds a database URL, injected so this file stays unaware of
 *               the storage layout
 */
export async function sweepBriefs(cfg, apiKey, path) {
  const existing = await fetch(path(cfg, "summaries")).then((r) => r.json());
  const done = new Set(Object.keys(existing || {}));
  const { briefBlocklist = "" } = await chrome.storage.local.get("briefBlocklist");
  const extraHosts = briefBlocklist
    .split(/[\s,]+/)
    .map((h) => h.trim().toLowerCase())
    .filter(Boolean);

  const tabs = await chrome.tabs.query({ windowType: "normal" });
  let written = 0;

  for (const tab of tabs) {
    if (written >= PER_TICK) break;
    if (!tab.url || !/^https?:/i.test(tab.url) || tab.pinned) continue;
    if (tab.status !== "complete" || tab.discarded) continue;

    const hash = await hashUrl(tab.url);
    if (done.has(hash)) continue;

    if (isPrivatePage(tab.url, extraHosts)) {
      // Recorded so it is not reconsidered every minute, and so the reason is
      // visible. Nothing about the page is sent anywhere.
      await store(cfg, path, hash, { status: "skipped", reason: "private page" });
      continue;
    }

    try {
      const page = await readPage(tab.id);
      if (page.text.length < MIN_CHARS || page.words < MIN_WORDS) {
        // A login wall, an app shell, a blank new tab.
        await store(cfg, path, hash, { status: "skipped", reason: "too little text" });
        continue;
      }
      if (!page.declaredArticle && page.linkDensity > 0.5) {
        // Mostly navigation: a feed, a search page, a console.
        await store(cfg, path, hash, { status: "skipped", reason: "not an article" });
        continue;
      }

      const brief = await askClaude(apiKey, {
        url: tab.url,
        title: page.title || tab.title || tab.url,
        text: page.text,
      });
      await store(cfg, path, hash, { status: "ok", ...brief });
      written += 1;
    } catch (err) {
      // Record the failure so the same page is not retried every minute.
      await store(cfg, path, hash, {
        status: "failed",
        error: String(err.message || err).slice(0, 300),
        createdAt: Date.now(),
      });
    }
  }

  return written;
}

async function store(cfg, path, hash, body) {
  await fetch(path(cfg, "summaries", hash), {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

