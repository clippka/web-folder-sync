import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { load, sha } from "./helpers.mjs";

const mod = await load("src/hash/sha256.ts");

test("incremental SHA-256 equals node:crypto at block boundaries and for split updates", () => {
  for (const n of [0, 1, 54, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 1000, 65536 + 3]) {
    const data = randomBytes(n);
    assert.equal(mod.sha256Hex(data), sha(data), `length ${n}`);
  }
  const data = randomBytes(10_000);
  const h = new mod.Sha256();
  for (let i = 0; i < data.length; ) { const step = 1 + ((i * 7) % 97); h.update(data.subarray(i, i + step)); i += step; }
  assert.equal(h.digestHex(), sha(data));
});

test("hashBlob streams a multi-megabyte file in slices and rejects cancellation", async () => {
  const data = randomBytes(3 * 1024 * 1024 + 17);
  assert.equal(await mod.hashBlob(new Blob([data]), () => false, 1 << 20), sha(data));
  await assert.rejects(mod.hashBlob(new Blob([data]), () => true), (e) => e.cancelled === true);
});
