import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { formatReset, parseUsageText, resetLabel, UsageTracker } from "../lib/usage.mjs";

const NOW = new Date(2026, 10, 24, 10, 0); // Tue 24 Nov 2026, 10:00 local time

describe("resetLabel", () => {
  it("formats a time of day", () => {
    assert.equal(resetLabel("4:59pm (Europe/Paris)", NOW), "at 16:59");
    assert.equal(resetLabel("12am", NOW), "at 00:00");
    assert.equal(resetLabel("9am", NOW), "at 09:00");
  });

  it("formats a later day", () => {
    assert.equal(resetLabel("Nov 28 at 1:59pm (Europe/Paris)", NOW), "Sat 28 at 13:59");
  });

  it("rolls over to next year", () => {
    assert.equal(resetLabel("Jan 3 at 9am", NOW), "Sun 3 at 09:00");
  });

  it("keeps unknown formats as they are", () => {
    assert.equal(resetLabel("tomorrow (UTC)", NOW), "tomorrow");
  });
});

describe("formatReset", () => {
  it("drops the day when the reset is close", () => {
    const inTwoHours = new Date(2026, 10, 24, 12, 30).getTime() / 1000;
    assert.equal(formatReset(inTwoHours, NOW.getTime()), "at 12:30");
  });
});

describe("parseUsageText", () => {
  it("reads every window of `claude /usage`", () => {
    const text = [
      "You are currently using your subscription to power your Claude Code usage",
      "",
      "Current session: 23% used · resets 4:59pm (Europe/Paris)",
      "Current week (all models): 8% used · resets Nov 28 at 2pm (Europe/Paris)",
      "Current week (Fable): 12.5% used · resets Nov 28 at 2pm (Europe/Paris)",
      "",
      "What's contributing to your usage?",
    ].join("\n");
    assert.deepEqual(parseUsageText(text, NOW), {
      five_hour: { label: "5h", pct: 23, resets: "at 16:59" },
      seven_day: { label: "Week", pct: 8, resets: "Sat 28 at 14:00" },
      seven_day_fable: { label: "Week (Fable)", pct: 12.5, resets: "Sat 28 at 14:00" },
    });
  });

  it("returns nothing for unrelated text", () => {
    assert.deepEqual(parseUsageText("Not logged in"), {});
  });
});

describe("UsageTracker", () => {
  it("applies rate limit events and keeps 5h before week", () => {
    const changes = [];
    const t = new UsageTracker("claude", (w) => changes.push(w), () => {});
    t.applyRateLimit({ unifiedWindows: { seven_day: { utilization: 0.071 }, five_hour: { utilization: 0.4 } } });
    assert.equal(changes.length, 1);
    assert.deepEqual(
      changes[0].map((w) => [w.key, w.label, w.pct]),
      [["five_hour", "5h", 40], ["seven_day", "Week", 7.1]],
    );
  });

  it("ignores events without utilization", () => {
    let called = false;
    const t = new UsageTracker("claude", () => (called = true), () => {});
    t.applyRateLimit({ status: "allowed" });
    assert.equal(called, false);
  });
});
