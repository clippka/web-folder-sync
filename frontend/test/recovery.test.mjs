// Interruption and resume: write-ahead journal, resumable uploads, and the last look before a file is replaced.
import { test } from "node:test";
import assert from "node:assert/strict";
import { load, FakeDir, CountingHasher, sha, utf8 } from "./helpers.mjs";

const { Api, MemoryStore, runSpace } = await load("test/entry.ts");

const LIMITS = { maxFileSizeBytes: 100 * 1024 * 1024, maxIncludedFiles: 25000, chunkSizeBytes: 4, maxManifestBatch: 1000 };
const SPACE = { id: "s1", name: "Space", revision: 1, policyRevision: 1, epoch: "E1", limits: LIMITS };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const plan = () => ({ id: "p1", state: "READY", phase: "CONTENT", serverRevision: 1, policyRevision: 1, epoch: "E1", paused: false, rescanRequired: false, scanComplete: true, counts: {}, decisionCount: 0, rejected: [], rejectedCount: 0, leaseId: null });
const op = (seq, kind, path, state = null, extra = {}) => ({ seq, kind, path, needsDecision: false, state, expected: null, reason: null, cause: null, blobId: null, ...extra });
const FILE = (bytes) => ({ type: "FILE", sha256: sha(bytes), size: bytes.length });
const COMMIT_NONE = { applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false };

function harness(routes) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const u = new URL(url, "http://x");
    const method = init.method ?? "GET";
    let body = init.body;
    if (typeof body === "string") body = JSON.parse(body);
    else if (body instanceof Blob) body = new Uint8Array(await body.arrayBuffer());
    const call = { method, path: u.pathname, body, headers: init.headers ?? {} };
    calls.push(call);
    for (const [key, handler] of routes) {
      const [m, re] = key.split(" ");
      if (m === method && new RegExp(`^${re}$`).test(u.pathname)) return handler(call);
    }
    throw new Error(`unrouted ${method} ${u.pathname}`);
  };
  return { api: new Api(fetchImpl, () => "csrf"), calls };
}

function context(h, root, store, extra = {}) {
  const binding = { spaceId: "s1", bindingId: "b1", deviceId: "d1", handle: root, rootName: "root", enrollmentGeneration: 1, epoch: "E1", connectedAt: 0 };
  return { api: h.api, store, hasher: new CountingHasher(), root, binding, signal: new AbortController().signal, ui: { phase() {}, progress() {}, file() {} }, ...extra };
}

const idle = (ops = [], extra = []) => [
  ["GET /api/spaces/s1", () => json(SPACE)],
  ["POST /api/spaces/s1/plans", () => json(plan())],
  ["GET /api/plans/p1/operations", () => json({ operations: ops, nextAfter: null })],
  ["POST /api/plans/p1/commit", () => json(COMMIT_NONE)],
  ["POST /api/plans/p1/acknowledgements", () => json({})],
  ...extra,
];

/** A store that dies right after a file was replaced, as if the browser were closed at that instant. */
class CrashingStore extends MemoryStore {
  crash = true;
  async putBaselines(spaceId, records) {
    if (this.crash && records.some((r) => r.path === "f.txt")) throw new Error("simulated crash");
    return super.putBaselines(spaceId, records);
  }
}

test("a download interrupted after the write is verified from the disk and recorded on the next run", async () => {
  const original = utf8("old"), wanted = utf8("new server text");
  const root = new FakeDir("root");
  root.put("f.txt", original);
  const store = new CrashingStore();
  await MemoryStore.prototype.putBaselines.call(store, "s1", [{ path: "f.txt", type: "FILE", sha256: sha(original), size: original.length }]);
  const dl = op(0, "DOWNLOAD", "f.txt", FILE(wanted), { blobId: "bl1", expected: FILE(original) });
  const h1 = harness(idle([dl], [["GET /api/spaces/s1/blobs/bl1/content", () => new Response(wanted)]]));
  const first = await runSpace(context(h1, root, store), "sync");
  assert.equal(first.status, "partial");
  assert.deepEqual(Buffer.from(root.read("f.txt")), Buffer.from(wanted), "the replacement happened");
  assert.equal((await store.journal("s1")).length, 1, "journal survives the crash");
  assert.equal((await store.baselines("s1")).get("f.txt").sha256, sha(original), "no baseline was advanced");

  store.crash = false;
  const h2 = harness(idle());
  const second = await runSpace(context(h2, root, store), "sync");
  assert.ok(second.notices.some((n) => n.startsWith("Resumed:")), second.notices.join("|"));
  assert.equal((await store.baselines("s1")).get("f.txt").sha256, sha(wanted), "baseline matches verified disk content");
  assert.equal((await store.journal("s1")).length, 0);
  assert.equal(h2.calls.filter((c) => c.path.includes("/blobs/")).length, 0, "nothing was downloaded again");
});

test("an entry whose write never started keeps the old baseline and the untouched file", async () => {
  const original = utf8("old"), wanted = utf8("newer");
  const root = new FakeDir("root");
  root.put("f.txt", original);
  const store = new MemoryStore();
  await store.putBaselines("s1", [{ path: "f.txt", type: "FILE", sha256: sha(original), size: original.length }]);
  await store.putJournal({ spaceId: "s1", path: "f.txt", planId: "p0", startedAt: 1, expected: { type: "FILE", ...FILE(original) }, intended: { sha256: sha(wanted), size: wanted.length } });
  const out = await runSpace(context(harness(idle()), root, store), "sync");
  assert.ok(out.notices.some((n) => n.includes("had not started writing")));
  assert.equal((await store.baselines("s1")).get("f.txt").sha256, sha(original));
  assert.deepEqual(Buffer.from(root.read("f.txt")), Buffer.from(original));
  assert.equal((await store.journal("s1")).length, 0);
});

test("content matching neither version is never overwritten and loses its baseline so it can only conflict", async () => {
  const original = utf8("old"), wanted = utf8("server"), edited = utf8("the user got here first");
  const root = new FakeDir("root");
  root.put("f.txt", edited);
  const store = new MemoryStore();
  await store.putBaselines("s1", [{ path: "f.txt", type: "FILE", sha256: sha(original), size: original.length }]);
  await store.putJournal({ spaceId: "s1", path: "f.txt", planId: "p0", startedAt: 1, expected: { type: "FILE", ...FILE(original) }, intended: { sha256: sha(wanted), size: wanted.length } });
  const h = harness(idle());
  const out = await runSpace(context(h, root, store), "sync");
  assert.ok(out.notices.some((n) => n.includes("did not match either version")));
  assert.deepEqual(Buffer.from(root.read("f.txt")), Buffer.from(edited));
  assert.equal((await store.baselines("s1")).has("f.txt"), false);
  const planReq = h.calls.find((c) => c.path.endsWith("/plans"));
  assert.equal(planReq.body.entries.find((e) => e.path === "f.txt").baseline, undefined, "planned as a first-time comparison");
});

test("a local edit made while the download streams is not replaced, and nothing is left to recover", async () => {
  const original = utf8("old text"), wanted = utf8("server text!");
  const root = new FakeDir("root");
  root.put("f.txt", original);
  const store = new MemoryStore();
  await store.putBaselines("s1", [{ path: "f.txt", type: "FILE", sha256: sha(original), size: original.length }]);
  const h = harness(idle([op(0, "DOWNLOAD", "f.txt", FILE(wanted), { blobId: "bl1", expected: FILE(original) })], [
    ["GET /api/spaces/s1/blobs/bl1/content", () => { root.put("f.txt", "edited meanwhile in an editor"); return new Response(wanted); }],
  ]));
  const out = await runSpace(context(h, root, store), "sync");
  assert.equal(out.status, "partial");
  assert.match(out.failures[0].message, /changed while downloading/);
  assert.equal(Buffer.from(root.read("f.txt")).toString(), "edited meanwhile in an editor");
  assert.equal((await store.baselines("s1")).get("f.txt").sha256, sha(original));
  assert.equal((await store.journal("s1")).length, 0, "an aborted write needs no recovery");
});

function uploadRoutes(state) {
  return [
    ["POST /api/spaces/s1/uploads", (c) => { state.keys.push(c.headers["Idempotency-Key"]); return json({ uploadId: state.uploadFor(c.headers["Idempotency-Key"]), state: "OPEN", size: 8, chunkSize: 4, chunkCount: 2, receivedChunks: [] }, 201); }],
    ["GET /api/uploads/[a-z0-9]+", (c) => { state.statusCalls++; return json(state.status(c.path.split("/").pop())); }],
    ["PUT /api/uploads/[a-z0-9]+/chunks/\\d+", (c) => state.chunk(c)],
    ["POST /api/uploads/[a-z0-9]+/complete", () => json({})],
  ];
}

const withCommit = (bytes, routes) => {
  const commit = { ...COMMIT_NONE, applied: [{ path: "a.bin", kind: "FILE", version: 1 }] };
  return routes.map(([k, hd]) => (k === "POST /api/plans/p1/commit" ? [k, () => json(commit)] : [k, hd]));
};

test("an interrupted upload keeps its key and staged chunks, and the next run sends only what is missing", async () => {
  const bytes = utf8("12345678");
  const root = new FakeDir("root");
  root.put("a.bin", bytes);
  const store = new MemoryStore();
  const ctl = new AbortController();
  const stored = [];
  const state = {
    keys: [], statusCalls: 0, uploadFor: () => "u1",
    status: () => ({ uploadId: "u1", state: "OPEN", size: 8, chunkSize: 4, chunkCount: 2, receivedChunks: stored.map((c, i) => (c ? i : -1)).filter((i) => i >= 0) }),
    chunk: (c) => {
      const i = Number(c.path.split("/").pop());
      if (!ctl.signal.aborted && i === 1) { ctl.abort(); throw Object.assign(new Error("aborted"), { name: "AbortError" }); }
      stored[i] = c.body;
      return json({ index: i, size: 4, duplicate: false });
    },
  };
  const routes = () => withCommit(bytes, idle([op(0, "UPLOAD", "a.bin", FILE(bytes))], uploadRoutes(state)));

  const first = await runSpace(context(harness(routes()), root, store, { signal: ctl.signal }), "sync");
  assert.equal(first.status, "cancelled");
  const record = await store.uploadRecord("s1", "a.bin");
  assert.ok(record, "the upload record outlives the cancelled run");
  assert.equal(record.key, state.keys[0]);

  const h2 = harness(routes());
  const second = await runSpace(context(h2, root, store), "sync");
  assert.equal(second.status, "completed");
  assert.equal(state.keys.length, 2);
  assert.equal(state.keys[1], state.keys[0], "the same begin key continues the same upload");
  assert.equal(state.statusCalls, 1);
  const puts = h2.calls.filter((c) => c.method === "PUT");
  assert.deepEqual(puts.map((c) => c.path), ["/api/uploads/u1/chunks/1"], "chunk 0 was not sent again");
  assert.deepEqual(Buffer.concat(stored), Buffer.from(bytes));
  assert.equal(await store.uploadRecord("s1", "a.bin"), undefined, "record released once the commit answered");
});

test("an earlier upload that was already used or has expired is replaced by a fresh one", async () => {
  const bytes = utf8("12345678");
  const root = new FakeDir("root");
  root.put("a.bin", bytes);
  const store = new MemoryStore();
  await store.putUploadRecord({ spaceId: "s1", path: "a.bin", sha256: sha(bytes), size: 8, key: "old-key", createdAt: 1 });
  const state = {
    keys: [], statusCalls: 0, uploadFor: (k) => (k === "old-key" ? "u1" : "u2"),
    status: (id) => ({ uploadId: id, state: "OPEN", size: 8, chunkSize: 4, chunkCount: 2, receivedChunks: [], consumed: id === "u1" }),
    chunk: () => json({ index: 0, size: 4, duplicate: false }),
  };
  const h = harness(withCommit(bytes, idle([op(0, "UPLOAD", "a.bin", FILE(bytes))], uploadRoutes(state))));
  const out = await runSpace(context(h, root, store), "sync");
  assert.equal(out.status, "completed");
  assert.equal(state.keys[0], "old-key");
  assert.notEqual(state.keys[1], "old-key");
  assert.deepEqual(h.calls.find((c) => c.path.endsWith("/commit")).body.uploads, { "a.bin": "u2" });
});

test("a changed file never reuses the upload of its earlier content", async () => {
  const bytes = utf8("12345678");
  const root = new FakeDir("root");
  root.put("a.bin", bytes);
  const store = new MemoryStore();
  await store.putUploadRecord({ spaceId: "s1", path: "a.bin", sha256: sha(utf8("other bytes")), size: 11, key: "old-key", createdAt: 1 });
  const state = { keys: [], statusCalls: 0, uploadFor: () => "u9", status: () => ({}), chunk: () => json({ index: 0, size: 4, duplicate: false }) };
  await runSpace(context(harness(withCommit(bytes, idle([op(0, "UPLOAD", "a.bin", FILE(bytes))], uploadRoutes(state)))), root, store), "sync");
  assert.equal(state.statusCalls, 0);
  assert.notEqual(state.keys[0], "old-key");
});
