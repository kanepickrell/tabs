// Entry point for the bundle. The MV3 service worker cannot resolve bare npm
// specifiers, so esbuild flattens the SDK into vendor/anthropic.bundle.js and
// background.js imports that by relative path.
export { default as Anthropic } from "@anthropic-ai/sdk";
