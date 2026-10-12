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

// Stamped on every record so the shape a brief was written in is knowable.
const BRIEF_VERSION = 3;

// Whether a shape change should rewrite briefs that already exist. Off: a
// bumped version only affects tabs briefed from now on, and every page already
// briefed keeps what it has. Rewriting the backlog costs a full sweep's worth
// of API calls, which is rarely what you want for a wording change.
const REISSUE_OLD_BRIEFS = false;

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
      short_title: {
        type: "string",
        description:
          "A clean title of at most six words. Strip site names, section " +
          "paths, file extensions and SEO padding. Say what the thing is, the " +
          "way a person would name it out loud.",
      },
      one_liner: {
        type: "string",
        description:
          "Exactly one sentence, at most 22 words, saying what this actually " +
          "argues, shows or offers. Not what it is 'about'. Punchy beats " +
          "complete: cut qualifiers and subordinate clauses rather than " +
          "packing the limit. No preamble, no hedging, no second sentence.",
      },
      question: {
        type: "string",
        description:
          "One thoughtful question to sit with before opening the page. It " +
          "should sharpen attention or provoke a real opinion - a tension in " +
          "the idea, a tradeoff worth having a view on, a comparison the page " +
          "may dodge. Never a comprehension check, never answerable by the " +
          "one-liner alone.",
      },
      reading_minutes: {
        type: "integer",
        description: "Rough minutes to read the full page at normal speed.",
      },
      is_worth_reading: {
        type: "boolean",
        description:
          "True if the full page rewards reading. False for thin, duplicated, " +
          "promotional or purely navigational pages.",
      },
    },
    required: [
      "short_title",
      "one_liner",
      "question",
      "reading_minutes",
      "is_worth_reading",
    ],
    additionalProperties: false,
  },
};

const SYSTEM = `You write one-glance cards for someone triaging their own open browser tabs,
so they can decide what to read instead of scrolling social media.

Three things only: a short title, one sentence, one question. Brevity is the
whole point - this is read in two seconds on a phone, between other things.

The sentence must be concrete. Give the actual claim, finding or offer. A
sentence that could have been written from the title alone is a failure, and so
is anything that says a page "explores", "discusses" or "dives into" a topic.

The question is the part that matters. It should be worth turning over in your
head on a walk, before the page is even open - a tension in the argument, a
tradeoff the reader should have a view on, something the page will probably
dodge. It must not be answerable from your own sentence, and it must never read
like a reading-comprehension exercise.`;

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

const ARXIV_PDF = /^https?:\/\/(?:www\.)?arxiv\.org\/pdf\/([^?#]+?)(?:v\d+)?(?:\.pdf)?$/i;

/**
 * Last resort for pages Chrome renders without a DOM we can walk. Only arXiv
 * is handled, because it is the one case with a guaranteed text equivalent at
 * a predictable URL. Other PDFs are still skipped.
 */
async function fallbackText(url) {
  const match = url.match(ARXIV_PDF);
  if (!match) return null;

  const html = await fetch(`https://arxiv.org/abs/${match[1]}`).then((r) =>
    r.ok ? r.text() : ""
  );
  if (!html) return null;

  // No DOMParser in a service worker, so pull the meta tags arXiv always sets.
  const meta = (name) =>
    html.match(
      new RegExp(`<meta[^>]+(?:name|property)=["']${name}["'][^>]+content=["']([^"']+)["']`, "i")
    )?.[1];

  const abstract = meta("citation_abstract") || meta("og:description");
  const title = meta("citation_title") || meta("og:title") || "";
  if (!abstract || abstract.length < MIN_CHARS) return null;

  const text = decodeEntities(abstract);
  return {
    text,
    title: decodeEntities(title),
    words: text.split(/\s+/).length,
    linkDensity: 0,
    declaredArticle: true,
  };
}

function decodeEntities(value) {
  return value
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)));
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
  const existing = (await fetch(path(cfg, "summaries")).then((r) => r.json())) || {};
  const done = new Set(
    Object.entries(existing)
      .filter(([, record]) => !REISSUE_OLD_BRIEFS || record?.v === BRIEF_VERSION)
      .map(([hash]) => hash)
  );
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
      let page = await readPage(tab.id);
      if (page.text.length < MIN_CHARS) {
        page = (await fallbackText(tab.url).catch(() => null)) ?? page;
      }
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
    body: JSON.stringify({ v: BRIEF_VERSION, ...body }),
  });
}

