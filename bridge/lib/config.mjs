import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import { expandHome } from "./util.mjs";

export class ConfigError extends Error {}

/** Reads and validates the bridge config. Every key except `token` and `host` has a default (see README). */
export function loadConfig(file) {
  if (!existsSync(file)) throw new ConfigError(`No config at ${file}. Copy config.example.json to config.json and fill it in.`);
  let raw;
  try {
    raw = JSON.parse(readFileSync(file, "utf8"));
  } catch (e) {
    throw new ConfigError(`${file} is not valid JSON: ${e.message}`);
  }
  if (!raw.token) throw new ConfigError(`${file}: "token" is required (run /claude link in game).`);
  if (!raw.direct && !raw.host) throw new ConfigError(`${file}: "host" is required (the Minecraft server to tunnel to).`);
  return {
    direct: raw.direct ?? false, // connect straight to host:remotePort, without SSH (local test server)
    host: raw.host,
    user: raw.user ?? "ubuntu",
    sshKey: expandHome(raw.sshKey ?? "~/.ssh/id_ed25519"),
    remotePort: raw.remotePort ?? 25580,
    localPort: raw.localPort ?? 25581,
    token: raw.token,
    hostLabel: raw.hostLabel ?? os.hostname(),
    claudePath: expandHome(raw.claudePath ?? "claude"),
    defaultCwd: expandHome(raw.defaultCwd ?? "~"),
    promptPrefix: raw.promptPrefix ?? "",
    permTimeoutSec: raw.permTimeoutSec ?? 300,
    keepAwake: raw.keepAwake ?? true,
    projectsDir: expandHome(raw.projectsDir ?? "~/.claude/projects"),
    maxHistory: raw.maxHistory ?? 200,
  };
}
