import assert from "node:assert/strict";
import os from "node:os";
import { describe, it } from "node:test";
import { diffPreview, summarizeInput } from "../lib/permissions.mjs";

describe("summarizeInput", () => {
  it("prefers the command or file of the tool call", () => {
    assert.equal(summarizeInput({ command: "npm   test\n--watch" }), "npm test --watch");
    assert.equal(summarizeInput({ file_path: `${os.homedir()}/project/a.js` }), "~/project/a.js");
  });

  it("falls back to the JSON input and truncates", () => {
    assert.equal(summarizeInput({ foo: 1 }), '{"foo":1}');
    assert.equal(summarizeInput({ command: "x".repeat(300) }).length, 160);
  });
});

describe("diffPreview", () => {
  it("counts lines of an edit", () => {
    const d = diffPreview("Edit", { old_string: "a\nb", new_string: "a\nB\nc" });
    assert.equal(d.plus, 3);
    assert.equal(d.minus, 2);
    assert.equal(d.detail, "- a\n- b\n+ a\n+ B\n+ c");
  });

  it("handles MultiEdit", () => {
    const d = diffPreview("MultiEdit", { edits: [{ old_string: "x", new_string: "y" }, { old_string: "1", new_string: "2" }] });
    assert.deepEqual([d.plus, d.minus], [2, 2]);
  });

  it("previews a new file", () => {
    const d = diffPreview("Write", { content: "# Title\n\nbody" });
    assert.deepEqual(d, { plus: 3, minus: 0, detail: "+ # Title\n+ \n+ body" });
  });

  it("shows the full command for Bash", () => {
    assert.equal(diffPreview("Bash", { command: "rm -rf build" }).detail, "rm -rf build");
  });
});
