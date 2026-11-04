import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";

test("coordinator guard allows exactly one slot per target", async () => {
  const out = await build({
    entryPoints: ["src/bindings/coordinator-guard.ts"], bundle: true, format: "esm", write: false,
  });
  const code = out.outputFiles[0].text;
  const mod = await import("data:text/javascript;base64," + Buffer.from(code).toString("base64"));
  const target = {};
  assert.ok(mod.acquireCoordinatorSlot(target));
  assert.equal(mod.acquireCoordinatorSlot(target), null);
});
