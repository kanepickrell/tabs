// Shared by background.js and brief.js. If these two ever disagreed, briefs
// would be stored under keys that no tab matches.
export async function hashUrl(url) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
  return [...new Uint8Array(digest)]
    .slice(0, 10)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
