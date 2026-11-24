// Folder listing for the in-game folder picker.
import { existsSync } from "node:fs";
import { readdir, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { expandHome, tildify } from "./util.mjs";

const MAX_ENTRIES = 400;

/** Resolves `~/x`, `/x` or a path relative to the home folder. */
export function resolveFolder(arg) {
  const p = expandHome(String(arg ?? "").trim() || "~");
  return path.resolve(path.isAbsolute(p) ? p : path.join(os.homedir(), p));
}

/**
 * Sub-folders of `arg` (hidden ones excluded, symlinks to folders included), each flagged when it is a git repository.
 * @returns {Promise<{path: string, parent: string, entries: {name: string, git: boolean}[], error: string}>}
 */
export async function listFolder(arg) {
  const dir = resolveFolder(arg);
  const result = { path: tildify(dir), parent: dir === path.parse(dir).root ? "" : tildify(path.dirname(dir)), entries: [], error: "" };
  try {
    const names = [];
    for (const it of await readdir(dir, { withFileTypes: true })) {
      if (it.name.startsWith(".")) continue;
      let isDir = it.isDirectory();
      if (!isDir && it.isSymbolicLink()) isDir = await stat(path.join(dir, it.name)).then((s) => s.isDirectory(), () => false);
      if (isDir) names.push(it.name);
    }
    names.sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
    result.entries = names.slice(0, MAX_ENTRIES).map((name) => ({ name, git: existsSync(path.join(dir, name, ".git")) }));
  } catch (e) {
    result.error =
      e.code === "ENOENT" ? "Folder not found"
      : e.code === "ENOTDIR" ? "Not a folder"
      : e.code === "EPERM" || e.code === "EACCES" ? "Access denied (check the OS privacy settings)"
      : e.message;
  }
  return result;
}
