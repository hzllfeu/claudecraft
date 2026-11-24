#!/usr/bin/env node
// Minimal MCP stdio server exposing one tool, `approve`, used by `claude -p --permission-prompt-tool
// mcp__claudecraft__approve`. Each call is forwarded to the bridge (local HTTP), which asks the player
// in Minecraft chat and returns {"behavior":"allow"} or {"behavior":"deny","message":...}.
import { createInterface } from "node:readline";

const URL_ = process.env.CC_PERM_URL;
const TOKEN = process.env.CC_PERM_TOKEN;
const CONV = process.env.CC_CONV;

const send = (obj) => process.stdout.write(JSON.stringify(obj) + "\n");
const reply = (id, result) => send({ jsonrpc: "2.0", id, result });
const fail = (id, code, message) => send({ jsonrpc: "2.0", id, error: { code, message } });

async function decide(args) {
  try {
    const res = await fetch(URL_, {
      method: "POST",
      headers: { "content-type": "application/json", "x-token": TOKEN },
      body: JSON.stringify({ ...args, conv: CONV }),
    });
    if (!res.ok) throw new Error(`bridge HTTP ${res.status}`);
    return await res.json();
  } catch (e) {
    return { behavior: "deny", message: `Permission bridge unavailable (${e.message}).` };
  }
}

createInterface({ input: process.stdin }).on("line", async (line) => {
  if (!line.trim()) return;
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  const { id, method, params } = msg;
  if (id === undefined || id === null) return; // notifications
  switch (method) {
    case "initialize":
      return reply(id, {
        protocolVersion: params?.protocolVersion ?? "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "claudecraft", version: "0.1.0" },
      });
    case "ping":
      return reply(id, {});
    case "tools/list":
      return reply(id, {
        tools: [
          {
            name: "approve",
            description: "Ask the user, in Minecraft chat, whether a tool call may run.",
            inputSchema: {
              type: "object",
              properties: {
                tool_name: { type: "string" },
                input: { type: "object" },
                tool_use_id: { type: "string" },
              },
              required: ["tool_name", "input"],
            },
          },
        ],
      });
    case "tools/call": {
      if (params?.name !== "approve") return fail(id, -32602, `Unknown tool ${params?.name}`);
      const decision = await decide(params.arguments ?? {});
      // Exactly one text block, no structuredContent (see Claude Code docs: errors#permission-prompt-tool…)
      return reply(id, { content: [{ type: "text", text: JSON.stringify(decision) }] });
    }
    default:
      return fail(id, -32601, `Method not found: ${method}`);
  }
});
