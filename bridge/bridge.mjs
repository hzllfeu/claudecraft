#!/usr/bin/env node
// ClaudeCraft bridge. Runs on the machine where you are logged in to Claude Code.
//
//  - Opens an SSH tunnel to the Minecraft server and authenticates with the player's token (/claude link).
//  - Each conversation runs the local `claude` CLI headless (`claude -p --resume <id> --output-format stream-json`).
//    Several conversations can run at once, each with its own folder, model and permission mode.
//  - Streams what Claude does back to the server, which shows it in the ClaudeCraft screen or in chat.
//
// Nothing Claude-related runs on the server: it only relays JSON lines between the game and this process.
import { execFile, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, readFileSync, statSync, writeFileSync } from "node:fs";
import http from "node:http";
import net from "node:net";
import path from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { ConfigError, loadConfig } from "./lib/config.mjs";
import { listFolder } from "./lib/folders.mjs";
import { diffPreview, summarizeInput } from "./lib/permissions.mjs";
import { listSessions, recentFolders, sessionPreview, timeAgo } from "./lib/sessions.mjs";
import { UsageTracker } from "./lib/usage.mjs";
import { expandHome, log, tildify, truncateWords } from "./lib/util.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = process.env.CC_CONFIG ? path.resolve(process.env.CC_CONFIG) : path.join(HERE, "config.json");
const STATE_PATH = process.env.CC_CONFIG ? CONFIG_PATH.replace(/\.json$/, "") + ".state.json" : path.join(HERE, "state.json");

let cfg;
try {
  cfg = loadConfig(CONFIG_PATH);
} catch (e) {
  if (!(e instanceof ConfigError)) throw e;
  console.error(e.message);
  process.exit(1);
}

const MODES = ["manual", "edits", "auto"];
const CLI_PERMISSION_MODE = { manual: null, edits: "acceptEdits", auto: "auto" };
const MODE_LABEL = { manual: "manual", edits: "auto-edits", auto: "auto" };
const OWN_TOOL_PREFIX = "mcp__claudecraft__"; // our permission tool: never shown to the player

if (cfg.keepAwake && process.platform === "darwin") {
  // no idle sleep while the bridge runs (closing the lid still sleeps unless plugged in with an external display)
  spawn("caffeinate", ["-i", "-w", String(process.pid)], { stdio: "ignore" }).unref();
}

// ============================================================================ transport

let tunnel = null;
let socket = null;

function startTunnel() {
  const args = [
    "-N", "-T", "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=20", "-o", "ServerAliveCountMax=3",
    "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-i", cfg.sshKey,
    "-L", `127.0.0.1:${cfg.localPort}:127.0.0.1:${cfg.remotePort}`, `${cfg.user}@${cfg.host}`,
  ];
  tunnel = spawn("ssh", args, { stdio: ["ignore", "ignore", "pipe"] });
  tunnel.stderr.on("data", (d) => log("[ssh]", d.toString().trim()));
  tunnel.on("exit", (code) => {
    log(`SSH tunnel closed (code ${code}), retrying in 5 s`);
    tunnel = null;
    setTimeout(startTunnel, 5000);
  });
  log(`SSH tunnel -> ${cfg.user}@${cfg.host}`);
}

/** Sends one event to the game. Returns false when the server is not connected. */
function emit(event) {
  if (!socket) return false;
  socket.write(JSON.stringify(event) + "\n");
  return true;
}

function connect() {
  const s = cfg.direct ? net.connect(cfg.remotePort, cfg.host) : net.connect(cfg.localPort, "127.0.0.1");
  s.setNoDelay(true);
  s.on("connect", () => {
    s.write(JSON.stringify({ type: "auth", token: cfg.token, host: cfg.hostLabel }) + "\n");
    socket = s;
    log("Connected to the Minecraft server");
    sendState();
    if (usage.updatedAt) sendUsage();
    usage.refresh();
  });
  const lines = createInterface({ input: s });
  lines.on("error", () => {}); // a reset socket is handled by "close" below
  lines.on("line", (line) => {
    let action;
    try {
      action = JSON.parse(line);
    } catch {
      return;
    }
    onAction(action).catch((e) => log("action error:", e));
  });
  s.on("error", () => {});
  s.on("close", () => {
    if (socket === s) {
      socket = null;
      log("Disconnected from the server");
      denyAllPending("Lost connection to the server.");
    }
    setTimeout(connect, 3000);
  });
}

// ============================================================================ conversations

/**
 * @typedef {object} Conv
 * @property {string} id
 * @property {string|null} title
 * @property {string} cwd absolute folder the CLI runs in
 * @property {string} branch git branch of cwd, if any
 * @property {string|null} sessionId Claude Code session, once the first turn started
 * @property {"manual"|"edits"|"auto"} mode
 * @property {string} model CLI model alias chosen by the player ("" = account default)
 * @property {string} modelName model actually used, from the CLI
 * @property {number} ctxUsed tokens in context at the last model call
 * @property {number} ctxWindow context window of that model (0 until known)
 * @property {"idle"|"thinking"|"waiting"|"error"} status
 * @property {string} activity
 * @property {{phase: string, detail: string, tokens: number}|null} live progress of the running turn
 * @property {import("node:child_process").ChildProcess|null} proc
 * @property {object[]} history last messages, replayed to the game on sync
 * @property {number} seq
 */

/** @type {Map<string, Conv>} */
const convs = new Map();
let focused = null;
let nextConvNumber = 1;
let lastSessionListing = [];

function summary(c) {
  return {
    id: c.id, title: c.title ?? "New conversation", project: path.basename(c.cwd), cwd: tildify(c.cwd), branch: c.branch ?? "",
    status: c.status, mode: c.mode, sessionId: c.sessionId ?? "", activity: c.activity ?? "",
    model: c.model ?? "", modelName: c.modelName ?? "", ctxUsed: c.ctxUsed ?? 0, ctxWindow: c.ctxWindow ?? 0,
  };
}

const notice = (text, style = "info") => emit({ type: "notice", text, style });
const sendState = () => emit({ type: "state", convs: [...convs.values()].map(summary), focused, host: cfg.hostLabel });
const sendUsage = () => emit({ type: "usage", at: usage.updatedAt, windows: usage.list() });

function pushConv(c) {
  emit({ type: "conv", conv: summary(c) });
  saveState();
}

function addMsg(c, m) {
  m.id = `${c.id}-${++c.seq}`;
  c.history.push(m);
  if (c.history.length > cfg.maxHistory) c.history.splice(0, c.history.length - cfg.maxHistory);
  emit({ type: "msg", conv: c.id, m });
  return m;
}

const updateMsg = (c, m) => emit({ type: "msg_update", conv: c.id, m });

function setStatus(c, status, activity = "") {
  c.status = status;
  c.activity = activity;
  pushConv(c);
}

/** Live progress of a running turn (throttled, not persisted). */
function emitLive(c, force = false) {
  const now = Date.now();
  if (!force && now - (c.liveAt ?? 0) < 300) return;
  c.liveAt = now;
  const l = c.live ?? {};
  emit({ type: "live", conv: c.id, phase: l.phase ?? "", detail: l.detail ?? "", tokens: l.tokens ?? 0 });
}

function setLive(c, phase, detail = "") {
  c.live ??= { tokens: 0 };
  c.live.phase = phase;
  c.live.detail = detail;
  emitLive(c, true);
}

function gitBranch(cwd) {
  return new Promise((resolve) =>
    execFile("git", ["-C", cwd, "branch", "--show-current"], { timeout: 3000 }, (err, out) => resolve(err ? "" : out.trim())),
  );
}

async function createConv({ cwd = cfg.defaultCwd, sessionId = null, title = null } = {}) {
  const c = {
    id: `c${nextConvNumber++}`, title, cwd, branch: await gitBranch(cwd), sessionId, mode: "manual",
    model: "", modelName: "", ctxUsed: 0, ctxWindow: 0, status: "idle", activity: "", live: null, proc: null, history: [], seq: 0,
  };
  convs.set(c.id, c);
  focused = c.id;
  pushConv(c);
  return c;
}

const resolveConv = (id) => (id && convs.get(id)) || (focused && convs.get(focused)) || null;

const PERSISTED = ["id", "title", "cwd", "sessionId", "mode", "model", "modelName", "ctxUsed", "ctxWindow", "seq"];

function saveState() {
  const data = {
    nextConvNumber,
    focused,
    convs: [...convs.values()].map((c) => ({
      ...Object.fromEntries(PERSISTED.map((k) => [k, c[k]])),
      history: c.history.slice(-cfg.maxHistory),
    })),
  };
  try {
    writeFileSync(STATE_PATH, JSON.stringify(data));
  } catch (e) {
    log("could not save state:", e.message);
  }
}

async function loadState() {
  if (!existsSync(STATE_PATH)) return;
  try {
    const data = JSON.parse(readFileSync(STATE_PATH, "utf8"));
    nextConvNumber = data.nextConvNumber ?? data.nextConv ?? 1;
    for (const saved of data.convs ?? []) {
      const s = Object.fromEntries(PERSISTED.map((k) => [k, saved[k]]));
      const cwd = s.cwd && existsSync(s.cwd) ? s.cwd : cfg.defaultCwd;
      convs.set(s.id, {
        ...s, model: s.model ?? "", modelName: s.modelName ?? "", ctxUsed: s.ctxUsed ?? 0, ctxWindow: s.ctxWindow ?? 0,
        cwd, branch: await gitBranch(cwd), status: "idle", activity: "", live: null, proc: null,
        history: saved.history ?? [], seq: s.seq ?? 0,
      });
    }
    focused = data.focused && convs.has(data.focused) ? data.focused : ([...convs.keys()].pop() ?? null);
    log(`Restored ${convs.size} conversation(s)`);
  } catch (e) {
    log("could not load state:", e.message);
  }
}

// ============================================================================ plan usage

const usage = new UsageTracker(cfg.claudePath, () => sendUsage(), log);
const USAGE_MAX_AGE = 2 * 60 * 1000;
setInterval(() => socket && usage.refresh(), 10 * 60 * 1000).unref();

let recentFoldersCache = { at: 0, folders: [] };
async function recentFoldersCached() {
  if (Date.now() - recentFoldersCache.at > 60_000) {
    recentFoldersCache = { at: Date.now(), folders: await recentFolders(cfg.projectsDir, 8) };
  }
  return recentFoldersCache.folders;
}

// ============================================================================ actions from the game

async function onAction(m) {
  switch (m.type) {
    case "sync": {
      sendState();
      for (const c of convs.values()) emit({ type: "history", conv: c.id, messages: c.history });
      for (const p of pending.values()) emit({ type: "perm", ...p.event });
      for (const c of convs.values()) if (c.live) emitLive(c, true);
      if (usage.updatedAt) sendUsage();
      if (usage.isStale(USAGE_MAX_AGE)) usage.refresh();
      return;
    }
    case "focus":
      if (convs.has(m.conv)) focused = m.conv;
      return;
    case "list_dirs":
      return emit({ type: "dirs", ...(await listFolder(m.path)), recent: await recentFoldersCached() });
    case "new_conv":
    case "new": {
      const arg = String(m.cwd ?? m.arg ?? "").trim();
      let dir = arg ? expandHome(arg) : cfg.defaultCwd;
      if (!path.isAbsolute(dir)) dir = path.join(cfg.defaultCwd, dir);
      if (!existsSync(dir) || !statSync(dir).isDirectory()) return notice(`Folder not found on your machine: ${dir}`, "error");
      const c = await createConv({ cwd: dir });
      return notice(`New conversation in ${tildify(c.cwd)}`);
    }
    case "prompt": {
      const text = String(m.text ?? "").trim();
      if (!text) return;
      const c = resolveConv(m.conv) ?? (await createConv());
      focused = c.id;
      return runTurn(c, text);
    }
    case "stop": {
      const c = resolveConv(m.conv);
      if (!c?.proc) return notice("Nothing is running.");
      c.proc.kill("SIGINT"); // ends the turn cleanly
      denyAllPending("Stopped from Minecraft.", c.id);
      return;
    }
    case "set_mode":
    case "mode": {
      const c = resolveConv(m.conv);
      if (!c) return notice("No conversation.", "error");
      const wanted = m.mode ?? m.arg;
      c.mode = MODES.includes(wanted) ? wanted : MODES[(MODES.indexOf(c.mode) + 1) % MODES.length];
      pushConv(c);
      return notice(`Mode ${MODE_LABEL[c.mode]} for ${c.title ?? c.id}${c.proc ? " (from the next message)" : ""}`);
    }
    case "set_model": {
      const c = resolveConv(m.conv);
      if (!c) return notice("No conversation.", "error");
      const wanted = String(m.model ?? "").trim();
      if (!/^[A-Za-z0-9._[\]-]{0,64}$/.test(wanted)) return notice("Invalid model name.", "error");
      c.model = wanted;
      if (wanted) c.modelName = "";
      pushConv(c);
      return notice(`Model ${wanted || "default"} for ${c.title ?? c.id}${c.proc ? " (from the next message)" : ""}`);
    }
    case "close_conv": {
      const c = convs.get(m.conv);
      if (!c) return;
      c.proc?.kill("SIGINT");
      denyAllPending("Conversation closed.", c.id);
      convs.delete(c.id);
      if (focused === c.id) focused = [...convs.keys()].pop() ?? null;
      emit({ type: "conv_closed", conv: c.id });
      saveState();
      return;
    }
    case "sessions": {
      lastSessionListing = await listSessions(cfg.projectsDir, 12, cfg);
      const open = new Set([...convs.values()].map((c) => c.sessionId));
      return emit({
        type: "sessions",
        items: lastSessionListing.map((s, i) => ({
          n: String(i + 1), id: s.id, title: s.title, project: s.project, ago: timeAgo(s.mtime), open: open.has(s.id),
        })),
      });
    }
    case "open_session":
    case "resume": {
      const arg = String(m.session ?? m.arg ?? "").trim();
      const s = (/^\d+$/.test(arg) ? lastSessionListing[Number(arg) - 1] : null)
        ?? (await listSessions(cfg.projectsDir, 300, cfg)).find((x) => x.id.startsWith(arg));
      if (!s) return notice(`Session "${arg}" not found.`, "error");
      const existing = [...convs.values()].find((c) => c.sessionId === s.id);
      if (existing) {
        focused = existing.id;
        return sendState();
      }
      const c = await createConv({ cwd: s.cwd && existsSync(s.cwd) ? s.cwd : cfg.defaultCwd, sessionId: s.id, title: s.title });
      addMsg(c, { role: "info", text: `Resumed session: ${s.title}` });
      for (const msg of await sessionPreview(s.file, 6, cfg)) addMsg(c, msg);
      return;
    }
    case "perm_reply": {
      const p = pending.get(m.id);
      if (!p) return;
      if (m.allow && m.always) p.always = true;
      const decision = m.allow ? { behavior: "allow" } : { behavior: "deny", message: "The user refused this action from Minecraft." };
      return resolvePending(m.id, decision, !!m.allow);
    }
    case "status": {
      const c = resolveConv(m.conv);
      return notice(c ? `${c.title ?? c.id} · ${tildify(c.cwd)} · mode ${c.mode} · ${c.status}` : "No conversation yet. /claude <message> starts one.");
    }
    default:
      log("unknown action", m.type);
  }
}

// ============================================================================ permission prompts
// perm-mcp.mjs (the CLI's --permission-prompt-tool) posts each request here and waits for the player's answer.

const PERM_TOKEN = randomBytes(18).toString("hex");
/** @type {Map<string, {conv: string, tool: string, event: object, timer: NodeJS.Timeout, always: boolean, respond: (d: object) => void}>} */
const pending = new Map();
/** conv id -> tools the player chose "Always" for */
const alwaysAllowed = new Map();
let permPort = 0;

function resolvePending(id, decision, allowed) {
  const p = pending.get(id);
  if (!p) return;
  clearTimeout(p.timer);
  pending.delete(id);
  if (allowed && p.always) {
    if (!alwaysAllowed.has(p.conv)) alwaysAllowed.set(p.conv, new Set());
    alwaysAllowed.get(p.conv).add(p.tool);
  }
  emit({ type: "perm_done", conv: p.conv, id, allowed });
  const c = convs.get(p.conv);
  if (c?.proc) setStatus(c, "thinking", c.activity);
  p.respond(decision);
}

function denyAllPending(reason, convId) {
  for (const [id, p] of pending) if (!convId || p.conv === convId) resolvePending(id, { behavior: "deny", message: reason }, false);
}

function onPermissionRequest(req, respond) {
  const c = convs.get(req.conv);
  if (req.tool_name === "AskUserQuestion") {
    return respond({ behavior: "deny", message: "The user is in Minecraft and cannot use the question UI. Ask your question as plain text in your reply instead." });
  }
  if (c && alwaysAllowed.get(c.id)?.has(req.tool_name)) return respond({ behavior: "allow" });
  if (!socket || !c) return respond({ behavior: "deny", message: "Nobody is connected in Minecraft to approve this." });

  const id = randomBytes(3).toString("hex");
  const event = { conv: c.id, id, tool: req.tool_name, summary: summarizeInput(req.input), ...diffPreview(req.tool_name, req.input) };
  const timer = setTimeout(() => {
    addMsg(c, { role: "info", text: `No answer: ${req.tool_name} denied.` });
    resolvePending(id, { behavior: "deny", message: "No answer from the user in Minecraft (timeout)." }, false);
  }, cfg.permTimeoutSec * 1000);
  pending.set(id, { conv: c.id, tool: req.tool_name, event, timer, always: false, respond });
  setStatus(c, "waiting", `${req.tool_name}: ${event.summary}`);
  emit({ type: "perm", ...event });
  emit({ type: "toast", conv: c.id, kind: "perm", title: `${c.title ?? summary(c).project} needs your permission`, text: `${req.tool_name} · ${event.summary}` });
}

const permServer = http.createServer((req, res) => {
  if (req.method !== "POST" || req.url !== "/perm" || req.headers["x-token"] !== PERM_TOKEN) return res.writeHead(403).end();
  let body = "";
  req.on("data", (d) => (body += d));
  req.on("end", () => {
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch {
      return res.writeHead(400).end();
    }
    onPermissionRequest(parsed, (decision) => res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(decision)));
  });
});

// ============================================================================ running a turn

function runTurn(c, text) {
  if (c.proc) return notice(`${c.title ?? c.id} is already running. Wait or stop it.`, "error");
  const mcpConfig = {
    mcpServers: {
      claudecraft: {
        command: process.execPath,
        args: [path.join(HERE, "perm-mcp.mjs")],
        env: { CC_PERM_URL: `http://127.0.0.1:${permPort}/perm`, CC_PERM_TOKEN: PERM_TOKEN, CC_CONV: c.id },
      },
    },
  };
  const args = [
    "-p", cfg.promptPrefix ? `${cfg.promptPrefix}\n\n${text}` : text,
    "--output-format", "stream-json", "--verbose", "--include-partial-messages",
    "--mcp-config", JSON.stringify(mcpConfig), "--permission-prompt-tool", `${OWN_TOOL_PREFIX}approve`,
  ];
  if (CLI_PERMISSION_MODE[c.mode]) args.push("--permission-mode", CLI_PERMISSION_MODE[c.mode]);
  if (c.model) args.push("--model", c.model);
  if (c.sessionId) args.push("--resume", c.sessionId);
  c.title ??= truncateWords(text, 40);

  log(`[${c.id}] claude ${c.sessionId ? `--resume ${c.sessionId.slice(0, 8)}` : "(new)"} mode=${c.mode} model=${c.model || "default"} in ${c.cwd}`);
  addMsg(c, { role: "user", text });
  const started = Date.now();
  const proc = spawn(cfg.claudePath, args, { cwd: c.cwd, env: process.env, stdio: ["ignore", "pipe", "pipe"] });
  c.proc = proc;
  c.live = { phase: "starting", detail: "", tokens: 0 };
  setStatus(c, "thinking", "starting");
  emitLive(c, true);

  const turn = new TurnStream(c);
  let stderrTail = "";
  proc.stderr.on("data", (d) => (stderrTail = (stderrTail + d.toString()).slice(-800)));
  const out = createInterface({ input: proc.stdout });
  out.on("error", () => {});
  out.on("line", (line) => {
    let m;
    try {
      m = JSON.parse(line);
    } catch {
      return;
    }
    if (m.type === "result") {
      turn.onResult(m);
      const secs = Math.round((Date.now() - started) / 1000);
      const ok = m.subtype === "success" && !m.is_error;
      const name = c.title ?? summary(c).project;
      addMsg(c, { role: "result", text: ok ? `✓ ${secs} s` : `Error (${m.subtype})${m.errors?.length ? ": " + m.errors.join("; ").slice(0, 200) : ""}`, ok });
      emit({ type: "toast", conv: c.id, kind: ok ? "done" : "error", title: ok ? `${name} finished` : `${name}: error`, text: `${secs} s` });
    } else {
      turn.onMessage(m);
    }
  });

  proc.on("exit", (code, signal) => {
    turn.finish();
    c.proc = null;
    c.live = null;
    emitLive(c, true);
    if (usage.isStale(USAGE_MAX_AGE)) setTimeout(() => usage.refresh(), 2000);
    denyAllPending("Turn finished.", c.id);
    const interrupted = signal === "SIGINT" || code === 130;
    if (!turn.gotResult) {
      if (interrupted) addMsg(c, { role: "info", text: "Interrupted." });
      else {
        addMsg(c, { role: "error", text: `claude exited (code ${code ?? signal}). ${stderrTail.trim().split("\n").pop() ?? ""}` });
        emit({ type: "toast", conv: c.id, kind: "error", title: `${c.title ?? c.id}: error`, text: `code ${code ?? signal}` });
      }
    }
    setStatus(c, turn.gotResult || interrupted ? "idle" : "error");
  });
  proc.on("error", (e) => {
    c.proc = null;
    c.live = null;
    emitLive(c, true);
    addMsg(c, { role: "error", text: `Could not start claude: ${e.message}` });
    setStatus(c, "error");
  });
}

/** Turns the CLI's stream-json messages of one turn into conversation messages and live progress. */
class TurnStream {
  constructor(c) {
    this.c = c;
    this.current = null; // message being streamed (assistant text or thinking)
    this.currentKind = null;
    this.currentStart = 0;
    this.tools = new Map(); // tool_use id -> tool message
    this.outputTokens = 0; // of the finished API calls
    this.streamedChars = 0; // of the current API call, ~4 chars per token
    this.gotResult = false;
  }

  onMessage(m) {
    const c = this.c;
    if (m.parent_tool_use_id != null) return; // sub-agent traffic
    switch (m.type) {
      case "system":
        if (m.subtype === "init") {
          if (m.model) c.modelName = m.model;
          if (m.session_id) c.sessionId = m.session_id;
          pushConv(c);
        } else if (m.subtype === "status" && m.status === "requesting" && !this.current && c.live?.phase !== "tool") {
          setLive(c, "requesting");
        }
        return;
      case "rate_limit_event":
        return usage.applyRateLimit(m.rate_limit_info);
      case "stream_event":
        return this.onStreamEvent(m.event ?? {});
      case "assistant":
        for (const b of m.message?.content ?? []) if (b.type === "tool_use") this.onToolCall(b);
        return;
      case "user":
        for (const b of m.message?.content ?? []) if (b.type === "tool_result") this.onToolResult(b);
        return;
    }
  }

  onStreamEvent(ev) {
    const c = this.c;
    switch (ev.type) {
      case "message_start": {
        const u = ev.message?.usage;
        if (u) c.ctxUsed = (u.input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0);
        if (ev.message?.model) c.modelName = ev.message.model;
        pushConv(c);
        break;
      }
      case "content_block_start": {
        const b = ev.content_block ?? {};
        if (b.type === "thinking" || b.type === "redacted_thinking") {
          this.startMessage("thinking");
          setLive(c, "thinking");
        } else if (b.type === "text") {
          this.endMessage();
          setLive(c, "writing");
        } else if (b.type === "tool_use" && !String(b.name).startsWith(OWN_TOOL_PREFIX)) {
          this.endMessage();
          this.tools.set(b.id, addMsg(c, { role: "tool", tool: b.name, text: "…", status: "running" }));
          setLive(c, "tool", b.name);
        }
        break;
      }
      case "content_block_delta": {
        const d = ev.delta ?? {};
        if (d.type === "text_delta") {
          if (this.currentKind !== "text") {
            this.startMessage("text");
            setLive(c, "writing");
          }
          this.append(d.text);
        } else if (d.type === "thinking_delta" && d.thinking) {
          if (this.currentKind === "thinking") this.append(d.thinking);
          else this.streamedChars += d.thinking.length;
        } else if (d.type === "input_json_delta") {
          this.streamedChars += (d.partial_json ?? "").length;
        }
        this.updateTokens();
        break;
      }
      case "content_block_stop":
        this.endMessage();
        break;
      case "message_delta":
        if (ev.usage?.output_tokens) {
          this.outputTokens += ev.usage.output_tokens;
          this.streamedChars = 0;
          this.updateTokens(true);
        }
        break;
    }
  }

  onToolCall(b) {
    if (b.name.startsWith(OWN_TOOL_PREFIX)) return;
    const c = this.c;
    let msg = this.tools.get(b.id);
    if (msg) {
      msg.text = summarizeInput(b.input);
      updateMsg(c, msg);
    } else {
      this.endMessage();
      msg = addMsg(c, { role: "tool", tool: b.name, text: summarizeInput(b.input), status: "running" });
      this.tools.set(b.id, msg);
    }
    setStatus(c, "thinking", `${b.name}: ${msg.text}`);
    setLive(c, "tool", `${b.name} · ${msg.text}`);
  }

  onToolResult(b) {
    const msg = this.tools.get(b.tool_use_id);
    if (!msg) return;
    msg.status = b.is_error ? "error" : "ok";
    updateMsg(this.c, msg);
    setStatus(this.c, "thinking", "thinking");
    setLive(this.c, "requesting");
  }

  onResult(m) {
    const c = this.c;
    this.gotResult = true;
    this.endMessage();
    if (m.session_id) c.sessionId = m.session_id;
    const perModel = m.modelUsage ?? {};
    const key = Object.keys(perModel).find((k) => c.modelName && c.modelName.startsWith(k)) ?? Object.keys(perModel)[0];
    if (key && perModel[key].contextWindow) c.ctxWindow = perModel[key].contextWindow;
  }

  /** The process ended: close what is still open. */
  finish() {
    this.endMessage();
    for (const msg of this.tools.values()) {
      if (msg.status !== "running") continue;
      msg.status = "error";
      updateMsg(this.c, msg);
    }
  }

  startMessage(kind) {
    this.endMessage();
    this.current = addMsg(this.c, { role: kind === "text" ? "assistant" : "thinking", text: "" });
    this.currentKind = kind;
    this.currentStart = Date.now();
  }

  append(text) {
    this.current.text += text;
    this.streamedChars += text.length;
    emit({ type: "delta", conv: this.c.id, id: this.current.id, text });
  }

  endMessage() {
    if (!this.current) return;
    this.current.final = true;
    if (this.currentKind === "thinking") this.current.secs = Math.max(1, Math.round((Date.now() - this.currentStart) / 1000));
    updateMsg(this.c, this.current);
    this.current = null;
    this.currentKind = null;
  }

  updateTokens(force = false) {
    this.c.live.tokens = this.outputTokens + Math.round(this.streamedChars / 4);
    emitLive(this.c, force);
  }
}

// ============================================================================ startup

await loadState();
permServer.listen(0, "127.0.0.1", () => {
  permPort = permServer.address().port;
  if (!cfg.direct) startTunnel();
  setTimeout(connect, 2000);
});

function shutdown() {
  denyAllPending("Bridge stopped.");
  for (const c of convs.values()) c.proc?.kill("SIGINT");
  saveState();
  tunnel?.removeAllListeners("exit");
  tunnel?.kill();
  setTimeout(() => process.exit(0), 300);
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
