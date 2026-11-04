import { test } from "node:test";
import assert from "node:assert/strict";
import { load, FakeDir, CountingHasher, sha, utf8 } from "./helpers.mjs";

const scanMod = await load("src/scan/index.ts");
const { scanFolder, buildPlanInput, OVERSIZED_PLACEHOLDER_SHA } = scanMod;

const opts = (root, hasher, extra = {}) => ({ root, hasher, maxFileSizeBytes: 100, cache: new Map(), signal: new AbortController().signal, ...extra });

test("ignored directories are pruned before anything below them is read or hashed", async () => {
  const root = new FakeDir("root");
  root.put(".gitignore", "node_modules/\nbuild\n");
  root.put("src/a.txt", "a");
  root.put("node_modules/pkg/index.js", "x".repeat(50));
  root.put("build/out.bin", "y");
  root.mkdir("empty/nested");
  root.put(".git/config", "g");
  const h = new CountingHasher();
  const r = await scanFolder(opts(root, h));
  assert.deepEqual([...r.entries.keys()].sort(), [".gitignore", "empty", "empty/nested", "src", "src/a.txt"]);
  assert.deepEqual(h.calls.sort(), [".gitignore", "a.txt"]);
  assert.ok(r.complete);
  const why = Object.fromEntries(r.ignored.map((i) => [i.path, i]));
  assert.equal(why["node_modules"].rule, "node_modules/");
  assert.equal(why[".git"].builtin, true);
});

test("nested ignore files apply below their own directory and are themselves synchronized", async () => {
  const root = new FakeDir("root");
  root.put("pkg/.syncignore", "*.log\n");
  root.put("pkg/keep.txt", "k");
  root.put("pkg/debug.log", "l");
  root.put("top.log", "t");
  const r = await scanFolder(opts(root, new CountingHasher()));
  assert.ok(r.entries.has("pkg/.syncignore"));
  assert.ok(r.entries.has("pkg/keep.txt") && r.entries.has("top.log"));
  assert.ok(!r.entries.has("pkg/debug.log"));
});

test("an unreadable directory makes the scan incomplete and is reported", async () => {
  const root = new FakeDir("root");
  root.put("ok.txt", "1");
  root.mkdir("locked").failListing = true;
  const r = await scanFolder(opts(root, new CountingHasher()));
  assert.equal(r.complete, false);
  assert.deepEqual(r.problems.map((p) => [p.path, p.kind]), [["locked", "directory"]]);
});

test("oversized files are not hashed and carry the placeholder digest", async () => {
  const root = new FakeDir("root");
  root.put("big.bin", new Uint8Array(101));
  root.put("exact.bin", new Uint8Array(100));
  const h = new CountingHasher();
  const r = await scanFolder(opts(root, h));
  assert.equal(r.entries.get("big.bin").oversized, true);
  assert.equal(r.entries.get("big.bin").sha256, OVERSIZED_PLACEHOLDER_SHA);
  assert.equal(r.entries.get("exact.bin").oversized, undefined);
  assert.deepEqual(h.calls, ["exact.bin"], "the exact-limit file is hashed, the larger one is not");
});

test("a verified hash is reused only while size and mtime are unchanged", async () => {
  const root = new FakeDir("root");
  root.put("f.txt", "hello", 5000);
  const h = new CountingHasher();
  const cache = new Map([["f.txt", { size: 5, mtime: 5000, sha256: sha(utf8("hello")) }]]);
  let r = await scanFolder(opts(root, h, { cache }));
  assert.equal(h.calls.length, 0);
  assert.equal(r.reusedHashCount, 1);
  root.put("f.txt", "HELLO", 5001);
  r = await scanFolder(opts(root, h, { cache }));
  assert.equal(h.calls.length, 1);
  assert.equal(r.entries.get("f.txt").sha256, sha(utf8("HELLO")));
});

test("cancellation stops the scan", async () => {
  const root = new FakeDir("root");
  root.put("a.txt", "a");
  const ctl = new AbortController();
  ctl.abort();
  await assert.rejects(scanFolder(opts(root, new CountingHasher(), { signal: ctl.signal })), (e) => e.cancelled === true);
});

// ---- plan input -------------------------------------------------------------------------------------------------

const F = (s) => ({ path: "", type: "FILE", sha256: sha(utf8(s)), size: s.length });

test("a baseline path that is simply gone is reported absent, which is the only way a deletion can be proposed", async () => {
  const root = new FakeDir("root");
  root.put("kept.txt", "k");
  const r = await scanFolder(opts(root, new CountingHasher()));
  const baselines = new Map([["kept.txt", { ...F("k"), path: "kept.txt" }], ["gone.txt", { ...F("g"), path: "gone.txt" }]]);
  const input = buildPlanInput(r, baselines);
  const gone = input.entries.find((e) => e.path === "gone.txt");
  assert.equal(gone.local, undefined);
  assert.equal(gone.unreadable, undefined);
});

test("baseline paths hidden by a local ignore rule, or inside an unreadable directory, are never reported absent", async () => {
  const root = new FakeDir("root");
  root.put(".gitignore", "secret/\n");
  root.put("secret/a.txt", "a");
  root.mkdir("locked").failListing = true;
  const r = await scanFolder(opts(root, new CountingHasher()));
  const baselines = new Map([
    ["secret/a.txt", { ...F("a"), path: "secret/a.txt" }],
    ["locked/b.txt", { ...F("b"), path: "locked/b.txt" }],
  ]);
  const input = buildPlanInput(r, baselines);
  for (const p of ["secret/a.txt", "locked/b.txt"]) assert.equal(input.entries.find((e) => e.path === p).unreadable, true, p);
  assert.deepEqual(input.suspendedPaths, ["secret/a.txt"]);
});

test("a path that was suspended and is back in scope is flagged as re-included", async () => {
  const root = new FakeDir("root");
  root.put("a.txt", "a");
  const r = await scanFolder(opts(root, new CountingHasher()));
  const input = buildPlanInput(r, new Map([["a.txt", { ...F("a"), path: "a.txt", suspended: true }], ["b.txt", { ...F("b"), path: "b.txt", suspended: true }]]));
  assert.equal(input.entries.find((e) => e.path === "a.txt").reincluded, true);
  assert.equal(input.entries.find((e) => e.path === "b.txt").reincluded, true);
});
