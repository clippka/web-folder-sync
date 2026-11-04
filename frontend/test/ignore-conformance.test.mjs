import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";

const built = await build({
  entryPoints: ["src/policy/ignore-policy.ts"], bundle: true, format: "esm", write: false,
});
const { IgnorePolicy } = await import(
  "data:text/javascript;base64," + Buffer.from(built.outputFiles[0].text).toString("base64")
);

const fixtures = JSON.parse(readFileSync(new URL("../../conformance/ignore-fixtures.json", import.meta.url), "utf8"));

for (const c of fixtures.cases) {
  test(`conformance: ${c.name}`, () => {
    const files = {};
    for (const [dir, f] of Object.entries(c.rules)) {
      files[dir] = { gitignore: f[".gitignore"] ?? null, syncignore: f[".syncignore"] ?? null };
    }
    const policy = new IgnorePolicy(files);
    for (const e of c.expect) {
      const d = policy.evaluate(e.path, e.dir);
      assert.equal(d.ignored, e.ignored, `${e.path} ignored`);
      const rule = !d.ignored ? null : d.builtin ? "builtin:.git" : `${d.rule.sourceFile}:${d.rule.line}`;
      assert.equal(rule, e.rule, `${e.path} rule`);
    }
  });
}
