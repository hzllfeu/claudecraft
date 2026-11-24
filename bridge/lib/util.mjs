import os from "node:os";
import path from "node:path";

/** `~/x` -> `/Users/me/x`. Other paths are returned unchanged. */
export function expandHome(p) {
  return p && p.startsWith("~") ? path.join(os.homedir(), p.slice(1)) : p;
}

/** `/Users/me/x` -> `~/x`, for display. */
export function tildify(p) {
  const home = os.homedir();
  return p === home || p.startsWith(home + path.sep) ? "~" + p.slice(home.length) : p;
}

/** Collapses whitespace and cuts to `max` characters with an ellipsis. */
export function truncate(text, max) {
  const one = String(text).replace(/\s+/g, " ").trim();
  return one.length > max ? one.slice(0, max - 1) + "…" : one;
}

/** Same, but cuts on a word boundary (for titles). */
export function truncateWords(text, max) {
  const one = String(text).replace(/\s+/g, " ").trim();
  return one.length > max ? one.slice(0, max - 1).replace(/\s+\S*$/, "") + "…" : one;
}

export function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}
