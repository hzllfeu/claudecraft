import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, utimesSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { ConfigError, loadConfig } from "../lib/config.mjs";
import { listFolder } from "../lib/folders.mjs";
import { listSessions, recentFolders, sessionPreview, timeAgo } from "../lib/sessions.mjs";

let tmp;
before(() => (tmp = mkdtempSync(path.join(os.tmpdir(), "claudecraft-test-"))));
after(() => rmSync(tmp, { recursive: true, force: true }));

const jsonl = (...records) => records.map((r) => JSON.stringify(r)).join("\n") + "\n";

describe("listFolder", () => {
  it("lists visible sub-folders, sorted, with git repositories flagged", async () => {
    const root = path.join(tmp, "folders");
    for (const d of ["beta", "Alpha/.git", ".hidden", "gamma"]) mkdirSync(path.join(root, d), { recursive: true });
    writeFileSync(path.join(root, "file.txt"), "");
    symlinkSync(path.join(root, "gamma"), path.join(root, "link-to-gamma"));

    const r = await listFolder(root);
    assert.equal(r.error, "");
    assert.equal(r.parent, path.dirname(root).replace(os.homedir(), "~"));
    assert.deepEqual(r.entries, [
      { name: "Alpha", git: true },
      { name: "beta", git: false },
      { name: "gamma", git: false },
      { name: "link-to-gamma", git: false },
    ]);
  });

  it("reports a missing folder", async () => {
    const r = await listFolder(path.join(tmp, "nope"));
    assert.equal(r.error, "Folder not found");
    assert.deepEqual(r.entries, []);
  });
});

describe("sessions", () => {
  let projects;
  let work;
  before(() => {
    projects = path.join(tmp, "projects");
    work = path.join(tmp, "work");
    mkdirSync(work);
    mkdirSync(path.join(projects, "-work"), { recursive: true });
    const older = path.join(projects, "-work", "aaaa.jsonl");
    writeFileSync(older, jsonl(
      { type: "user", cwd: work, message: { content: "[prefix]\n\nFix the login bug" } },
      { type: "assistant", message: { content: [{ type: "text", text: "Fixed." }] } },
    ));
    utimesSync(older, new Date(2026, 0, 1), new Date(2026, 0, 1));
    writeFileSync(path.join(projects, "-work", "bbbb.jsonl"), jsonl(
      { type: "user", cwd: work, isMeta: true, message: { content: "<command-name>/clear</command-name>" } },
      { type: "user", cwd: work, message: { content: [{ type: "tool_result", content: "x" }] } },
      { type: "summary", summary: "Refactor the parser" },
      { type: "user", cwd: work, message: { content: "Split parser.js in two" } },
      { type: "assistant", message: { content: [{ type: "text", text: "Done, see parser/." }] } },
    ));
  });

  it("lists the most recent first, with a title", async () => {
    const list = await listSessions(projects, 10, { promptPrefix: "[prefix]" });
    assert.deepEqual(list.map((s) => [s.id, s.title, s.project]), [
      ["bbbb", "Refactor the parser", "work"],
      ["aaaa", "Fix the login bug", "work"],
    ]);
  });

  it("previews the last messages without meta or tool results", async () => {
    const msgs = await sessionPreview(path.join(projects, "-work", "bbbb.jsonl"), 6);
    assert.deepEqual(msgs, [
      { role: "user", text: "Split parser.js in two" },
      { role: "assistant", text: "Done, see parser/.", final: true },
    ]);
  });

  it("collects distinct recent folders", async () => {
    assert.deepEqual(await recentFolders(projects, 5), [work.replace(os.homedir(), "~")]);
  });

  it("returns nothing for a missing projects folder", async () => {
    assert.deepEqual(await listSessions(path.join(tmp, "missing"), 10), []);
  });

  it("formats ages", () => {
    const now = Date.now();
    assert.equal(timeAgo(now - 30_000, now), "just now");
    assert.equal(timeAgo(now - 5 * 60_000, now), "5 min");
    assert.equal(timeAgo(now - 3 * 3600_000, now), "3 h");
    assert.equal(timeAgo(now - 2 * 86400_000, now), "2 d");
  });
});

describe("loadConfig", () => {
  it("applies defaults", () => {
    const file = path.join(tmp, "config.json");
    writeFileSync(file, JSON.stringify({ host: "mc.example.com", token: "t" }));
    const cfg = loadConfig(file);
    assert.equal(cfg.remotePort, 25580);
    assert.equal(cfg.permTimeoutSec, 300);
    assert.equal(cfg.defaultCwd, os.homedir());
  });

  it("rejects a missing token or host", () => {
    const file = path.join(tmp, "bad.json");
    writeFileSync(file, JSON.stringify({ host: "mc.example.com" }));
    assert.throws(() => loadConfig(file), ConfigError);
    writeFileSync(file, JSON.stringify({ token: "t" }));
    assert.throws(() => loadConfig(file), ConfigError);
    writeFileSync(file, JSON.stringify({ token: "t", direct: true, host: "127.0.0.1" }));
    assert.doesNotThrow(() => loadConfig(file));
  });

  it("rejects a missing file", () => {
    assert.throws(() => loadConfig(path.join(tmp, "none.json")), ConfigError);
  });
});
