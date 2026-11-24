// Plan usage (5-hour session, week…) as reported by Claude Code.
// Two sources: `claude -p /usage` (a local command: no model call, no cost) and the `rate_limit_event`
// messages a running turn emits, which carry fresher numbers for the windows they mention.
import { execFile } from "node:child_process";
import os from "node:os";

const ORDER = ["five_hour", "seven_day"];
const LABELS = { five_hour: "5h", seven_day: "Week" };
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Epoch seconds -> "at 14:00" (within ~a day) or "Sat 28 at 14:00". */
export function formatReset(epochSec, now = Date.now()) {
  const d = new Date(epochSec * 1000);
  const hm = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  if (d - now < 20 * 3600 * 1000) return `at ${hm}`;
  return `${d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric" })} at ${hm}`;
}

/** The CLI's "Nov 28 at 1:59pm (Europe/Paris)" or "4:59pm (Europe/Paris)" -> formatReset style. */
export function resetLabel(text, now = new Date()) {
  const t = text.replace(/\s*\([^)]*\)\s*$/, "").trim();
  const to24h = (h, m, ampm) => [(Number(h) % 12) + (ampm.toLowerCase() === "pm" ? 12 : 0), Number(m ?? 0)];
  let m = t.match(/^([A-Z][a-z]{2}) (\d{1,2}) at (\d{1,2})(?::(\d{2}))?\s*(am|pm)$/i);
  if (m && MONTHS.includes(m[1])) {
    const month = MONTHS.indexOf(m[1]);
    const [h, min] = to24h(m[3], m[4], m[5]);
    let d = new Date(now.getFullYear(), month, Number(m[2]), h, min);
    if (d < now - 180 * 86400 * 1000) d = new Date(now.getFullYear() + 1, month, Number(m[2]), h, min); // across new year
    return formatReset(d.getTime() / 1000, now.getTime());
  }
  m = t.match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)$/i);
  if (m) {
    const [h, min] = to24h(m[1], m[2], m[3]);
    return `at ${String(h).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
  }
  return t;
}

/** Parses the text of `claude /usage` into { key: { label, pct, resets } }. */
export function parseUsageText(text, now = new Date()) {
  const out = {};
  for (const m of text.matchAll(/^Current ([^:\n]+):\s*(\d+(?:\.\d+)?)% used(?:\s*·\s*resets ([^\n]+))?/gm)) {
    const name = m[1].trim();
    let key;
    let label;
    if (/^session/i.test(name)) [key, label] = ["five_hour", LABELS.five_hour];
    else if (/^week( \(all[^)]*\))?$/i.test(name)) [key, label] = ["seven_day", LABELS.seven_day];
    else {
      const scope = name.match(/\(([^)]+)\)/)?.[1] ?? name;
      key = "seven_day_" + scope.toLowerCase().replace(/\W+/g, "_");
      label = `Week (${scope})`;
    }
    out[key] = { label, pct: Number(m[2]), resets: m[3] ? resetLabel(m[3], now) : "" };
  }
  return out;
}

export class UsageTracker {
  /**
   * @param {string} claudePath path to the CLI
   * @param {(windows: object[]) => void} onChange called with the ordered windows whenever they change
   * @param {(...args: unknown[]) => void} log
   */
  constructor(claudePath, onChange, log) {
    this.claudePath = claudePath;
    this.onChange = onChange;
    this.log = log;
    this.windows = {};
    this.updatedAt = 0;
    this.busy = false;
  }

  /** Windows ordered for display: 5h, week, then model-specific weeks. */
  list() {
    const rank = (k) => (ORDER.includes(k) ? ORDER.indexOf(k) : ORDER.length);
    return Object.keys(this.windows)
      .sort((a, b) => rank(a) - rank(b))
      .map((key) => ({ key, ...this.windows[key] }));
  }

  isStale(maxAgeMs) {
    return Date.now() - this.updatedAt > maxAgeMs;
  }

  refresh() {
    if (this.busy) return;
    this.busy = true;
    const args = ["-p", "/usage", "--output-format", "json", "--no-session-persistence"];
    execFile(this.claudePath, args, { cwd: os.tmpdir(), timeout: 45_000, maxBuffer: 4 * 1024 * 1024 }, (err, stdout) => {
      this.busy = false;
      if (err) return this.log("usage refresh failed:", err.message.split("\n")[0]);
      try {
        const windows = parseUsageText(JSON.parse(stdout).result ?? "");
        if (!Object.keys(windows).length) return this.log("usage: nothing to parse in `claude /usage`");
        this.windows = windows;
        this.updatedAt = Date.now();
        this.onChange(this.list());
      } catch (e) {
        this.log("usage parse error:", e.message);
      }
    });
  }

  /** `rate_limit_event.rate_limit_info` from a running turn. */
  applyRateLimit(info) {
    let changed = false;
    for (const [key, w] of Object.entries(info?.unifiedWindows ?? {})) {
      if (typeof w?.utilization !== "number") continue;
      const cur = this.windows[key] ?? { label: LABELS[key] ?? key, pct: 0, resets: "" };
      cur.pct = Math.round(w.utilization * 1000) / 10;
      if (w.resetsAt) cur.resets = formatReset(w.resetsAt);
      this.windows[key] = cur;
      changed = true;
    }
    if (changed) this.onChange(this.list());
  }
}
