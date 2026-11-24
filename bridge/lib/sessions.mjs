// Claude Code sessions stored on disk (<projectsDir>/<encoded cwd>/<session id>.jsonl).
import { existsSync } from "node:fs";
import { open, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { tildify, truncate } from "./util.mjs";

const HEAD_BYTES = 256 * 1024;
const TAIL_BYTES = 128 * 1024;
const PREVIEW_BYTES = 400 * 1024;

async function readSlice(file, start, length) {
  const fh = await open(file, "r");
  try {
    const buf = Buffer.alloc(length);
    const { bytesRead } = await fh.read(buf, 0, length, start);
    return buf.subarray(0, bytesRead).toString("utf8");
  } finally {
    await fh.close();
  }
}

function* records(text) {
  for (const line of text.split("\n")) {
    if (!line.startsWith("{")) continue;
    try {
      yield JSON.parse(line);
    } catch {
      // a line cut by the read window
    }
  }
}

const textOf = (content) =>
  typeof content === "string"
    ? content
    : Array.isArray(content)
      ? content.filter((x) => x?.type === "text").map((x) => x.text).join(" ")
      : "";

/** Text the user typed, without the bridge's prompt prefix; "" for tool results and meta messages. */
function userText(record, promptPrefix) {
  if (record.type !== "user" || record.isMeta || record.isSidechain) return "";
  let t = textOf(record.message?.content).trim();
  if (promptPrefix && t.startsWith(promptPrefix)) t = t.slice(promptPrefix.length).trim();
  return t.startsWith("<") ? "" : t;
}

/** Most recent sessions first: { id, file, title, cwd, project, mtime }. */
export async function listSessions(projectsDir, limit, { promptPrefix = "" } = {}) {
  let projects;
  try {
    projects = await readdir(projectsDir);
  } catch {
    return [];
  }
  const files = [];
  for (const project of projects) {
    const dir = path.join(projectsDir, project);
    let names;
    try {
      names = await readdir(dir);
    } catch {
      continue;
    }
    for (const name of names.filter((n) => n.endsWith(".jsonl"))) {
      const file = path.join(dir, name);
      try {
        const s = await stat(file);
        if (s.isFile() && s.size > 0) files.push({ file, id: name.slice(0, -6), mtime: s.mtimeMs, size: s.size });
      } catch {
        // removed meanwhile
      }
    }
  }
  files.sort((a, b) => b.mtime - a.mtime);

  const out = [];
  for (const f of files.slice(0, limit)) {
    const head = await readSlice(f.file, 0, Math.min(f.size, HEAD_BYTES));
    const tail = f.size > HEAD_BYTES ? await readSlice(f.file, f.size - TAIL_BYTES, TAIL_BYTES) : "";
    let cwd = null;
    let first = "";
    let title = null;
    for (const r of records(head + "\n" + tail)) {
      cwd ??= r.cwd ?? null;
      first ||= userText(r, promptPrefix);
      if (r.type === "custom-title" && r.customTitle) title = r.customTitle;
      else if (r.type === "summary" && r.summary && !title) title = r.summary;
    }
    out.push({
      id: f.id, file: f.file, title: truncate(title ?? (first || "(untitled)"), 48), cwd,
      project: cwd ? path.basename(cwd) : "?", mtime: f.mtime,
    });
  }
  return out;
}

/** Last `n` user/assistant messages of a session, so a resumed conversation is not empty. */
export async function sessionPreview(file, n, { promptPrefix = "" } = {}) {
  try {
    const { size } = await stat(file);
    const text = await readSlice(file, Math.max(0, size - PREVIEW_BYTES), Math.min(size, PREVIEW_BYTES));
    const msgs = [];
    for (const r of records(text)) {
      if (r.isSidechain || r.isMeta) continue;
      if (r.type === "user") {
        const t = userText(r, promptPrefix);
        if (t) msgs.push({ role: "user", text: t.slice(0, 600) });
      } else if (r.type === "assistant") {
        const t = textOf(r.message?.content).trim();
        if (t) msgs.push({ role: "assistant", text: t.slice(0, 1200), final: true });
      }
    }
    return msgs.slice(-n);
  } catch {
    return [];
  }
}

/** Distinct, existing working folders of the most recent sessions, as `~/…` paths. */
export async function recentFolders(projectsDir, max, scan = 80) {
  const seen = new Set();
  for (const s of await listSessions(projectsDir, scan)) {
    if (s.cwd && !seen.has(s.cwd) && existsSync(s.cwd)) seen.add(s.cwd);
    if (seen.size >= max) break;
  }
  return [...seen].map(tildify);
}

export function timeAgo(ms, now = Date.now()) {
  const s = Math.round((now - ms) / 1000);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min`;
  if (s < 86400) return `${Math.round(s / 3600)} h`;
  return `${Math.round(s / 86400)} d`;
}
