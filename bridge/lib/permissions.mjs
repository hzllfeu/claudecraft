// How tool calls are described to the player when Claude asks for permission.
import os from "node:os";
import { truncate } from "./util.mjs";

/** One line describing a tool call: the command, file, URL or pattern it acts on. */
export function summarizeInput(input = {}) {
  const subject =
    input.command ?? input.file_path ?? input.notebook_path ?? input.url ?? input.pattern ?? input.query ?? input.description ?? JSON.stringify(input);
  return truncate(String(subject).replaceAll(os.homedir(), "~"), 160);
}

const lines = (text) => (text ? String(text).split("\n") : []);

/** Added/removed line counts and a short preview of what the tool call would change. */
export function diffPreview(tool, input = {}) {
  if (tool === "Edit" || tool === "MultiEdit") {
    const edits = input.edits ?? [{ old_string: input.old_string, new_string: input.new_string }];
    let plus = 0;
    let minus = 0;
    const detail = [];
    for (const e of edits) {
      const before = lines(e.old_string);
      const after = lines(e.new_string);
      minus += before.length;
      plus += after.length;
      detail.push(...before.slice(0, 12).map((l) => "- " + l), ...after.slice(0, 12).map((l) => "+ " + l));
    }
    return { plus, minus, detail: detail.slice(0, 40).join("\n") };
  }
  if (tool === "Write") {
    const added = lines(input.content);
    return { plus: added.length, minus: 0, detail: added.slice(0, 30).map((l) => "+ " + l).join("\n") };
  }
  if (tool === "Bash") return { plus: 0, minus: 0, detail: String(input.command ?? "") };
  return { plus: 0, minus: 0, detail: JSON.stringify(input, null, 1).slice(0, 1200) };
}
