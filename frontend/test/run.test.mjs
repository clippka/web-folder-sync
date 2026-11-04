// Orchestration tests: the real run loop against an in-memory folder, an in-memory store and a scripted server.
import { test } from "node:test";
import assert from "node:assert/strict";
import { load, FakeDir, CountingHasher, sha, utf8 } from "./helpers.mjs";

const { Api, MemoryStore, runSpace } = await load("test/entry.ts");

const LIMITS = { maxFileSizeBytes: 100 * 1024 * 1024, maxIncludedFiles: 25000, chunkSizeBytes: 4, maxManifestBatch: 1000 };
const SPACE = { id: "s1", name: "Space", revision: 1, policyRevision: 1, epoch: "E1", limits: LIMITS };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const err = (status, code, details = {}) => json({ code, message: code, retryable: status >= 500, details }, status);

function plan(extra = {}) {
  return { id: "p1", state: "READY", phase: "CONTENT", serverRevision: 1, policyRevision: 1, epoch: "E1", paused: false, rescanRequired: false, scanComplete: true, counts: {}, decisionCount: 0, rejected: [], rejectedCount: 0, leaseId: null, ...extra };
}
const op = (seq, kind, path, state = null, extra = {}) => ({ seq, kind, path, needsDecision: false, state, expected: null, reason: null, cause: null, blobId: null, ...extra });
const FILE = (bytes) => ({ type: "FILE", sha256: sha(bytes), size: bytes.length });

/** routes: "METHOD /path-regex" -> handler(call) returning a Response. Every request is recorded. */
function harness(routes) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const u = new URL(url, "http://x");
    const method = init.method ?? "GET";
    let body = init.body;
    if (typeof body === "string") body = JSON.parse(body);
    else if (body instanceof Blob) body = new Uint8Array(await body.arrayBuffer());
    const call = { method, path: u.pathname, query: u.searchParams, body, headers: init.headers };
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
  const events = [];
  return {
    ctx: { api: h.api, store, hasher: new CountingHasher(), root, binding, signal: new AbortController().signal, ui: { phase() {}, progress() {}, file: (p, s, d) => events.push([p, s, d]) }, ...extra },
    events,
  };
}

test("first sync uploads files in chunks and creates directories, then records baselines and acknowledgements", async () => {
  const root = new FakeDir("root");
  const bytes = utf8("a\r\nb"); // CRLF must survive untouched
  root.put("a.txt", bytes);
  root.mkdir("d/empty");
  const store = new MemoryStore();
  const chunks = [];
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan())],
    ["GET /api/plans/p1/operations", () => json({ operations: [op(0, "UPLOAD", "a.txt", FILE(bytes)), op(1, "UPLOAD", "d", { type: "DIRECTORY" }), op(2, "UPLOAD", "d/empty", { type: "DIRECTORY" })], nextAfter: null })],
    ["POST /api/spaces/s1/uploads", () => json({ uploadId: "u1", state: "OPEN", size: 4, chunkSize: 4, chunkCount: 1, receivedChunks: [] }, 201)],
    ["PUT /api/uploads/u1/chunks/\\d+", (c) => { chunks.push(c.body); return json({ index: 0, size: c.body.length, duplicate: false }); }],
    ["POST /api/uploads/u1/complete", () => json({})],
    ["POST /api/plans/p1/commit", () => json({ applied: [{ path: "a.txt", kind: "FILE", version: 1 }, { path: "d", kind: "DIRECTORY", version: 1 }, { path: "d/empty", kind: "DIRECTORY", version: 1 }], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
    ["POST /api/plans/p1/acknowledgements", () => json({ recorded: 3 })],
  ]);
  const { ctx } = context(h, root, store);
  const out = await runSpace(ctx, "sync");

  assert.equal(out.status, "completed");
  assert.equal(out.done.uploaded, 1);
  assert.equal(out.done.directories, 2);
  assert.deepEqual(Buffer.concat(chunks), Buffer.from(bytes));
  const commit = h.calls.find((c) => c.path.endsWith("/commit"));
  assert.deepEqual(commit.body.uploads, { "a.txt": "u1" });
  assert.ok(commit.headers["Idempotency-Key"], "commit carries an idempotency key");
  assert.equal(commit.headers["X-XSRF-TOKEN"], "csrf");
  const base = await store.baselines("s1");
  assert.equal(base.get("a.txt").sha256, sha(bytes));
  assert.equal(base.get("d/empty").type, "DIRECTORY");
  // First enrollment: nothing in the manifest has a baseline.
  const planReq = h.calls.find((c) => c.path.endsWith("/plans"));
  assert.ok(planReq.body.entries.every((e) => e.baseline === undefined));
  assert.equal(planReq.body.scanComplete, true);
});

test("scan is a preview: no upload, commit or local change happens", async () => {
  const root = new FakeDir("root");
  root.put("a.txt", "x");
  const store = new MemoryStore();
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan({ leaseId: "L1" }))],
    ["GET /api/plans/p1/operations", () => json({ operations: [op(0, "UPLOAD", "a.txt", FILE(utf8("x"))), op(1, "DOWNLOAD", "b.txt", FILE(utf8("b")), { blobId: "bl" })], nextAfter: null })],
    ["DELETE /api/leases/L1", () => new Response(null, { status: 204 })],
  ]);
  const { ctx } = context(h, root, store);
  const out = await runSpace(ctx, "scan");
  assert.equal(out.status, "preview");
  assert.deepEqual(h.calls.map((c) => c.method + " " + c.path), ["GET /api/spaces/s1", "POST /api/spaces/s1/plans", "GET /api/plans/p1/operations", "DELETE /api/leases/L1"]);
  assert.equal(root.has("b.txt"), false);
  assert.equal((await store.baselines("s1")).size, 0);
});

test("downloads are written byte-exact, parents first, and only then recorded", async () => {
  const root = new FakeDir("root");
  const bytes = Buffer.from([0x61, 0x0d, 0x0a, 0x00, 0xff, 0xe9]);
  const store = new MemoryStore();
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan({ leaseId: "L1" }))],
    ["GET /api/plans/p1/operations", () => json({ operations: [op(0, "DOWNLOAD", "x", { type: "DIRECTORY" }), op(1, "DOWNLOAD", "x/y.bin", FILE(bytes), { blobId: "bl1", expected: { type: "ABSENT" } })], nextAfter: null })],
    ["POST /api/plans/p1/commit", () => json({ applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
    ["GET /api/spaces/s1/blobs/bl1/content", () => new Response(bytes)],
    ["POST /api/plans/p1/acknowledgements", () => json({})],
  ]);
  const { ctx } = context(h, root, store);
  const out = await runSpace(ctx, "sync");
  assert.equal(out.status, "completed");
  assert.deepEqual(Buffer.from(root.read("x/y.bin")), bytes);
  const base = await store.baselines("s1");
  assert.equal(base.get("x/y.bin").sha256, sha(bytes));
  assert.equal(base.get("x").type, "DIRECTORY");
  const ack = h.calls.find((c) => c.path.endsWith("/acknowledgements"));
  assert.deepEqual(ack.body.entries.map((e) => e.path), ["x", "x/y.bin"]);
});

test("corrupt download content never replaces or creates a file and records no baseline", async () => {
  const root = new FakeDir("root");
  root.put("f.txt", "local original");
  const original = utf8("local original");
  const wanted = utf8("server version");
  const store = new MemoryStore();
  await store.putBaselines("s1", [{ path: "f.txt", type: "FILE", sha256: sha(original), size: original.length }]);
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan())],
    ["GET /api/plans/p1/operations", () => json({ operations: [
      op(0, "DOWNLOAD", "f.txt", FILE(wanted), { blobId: "bl1", expected: FILE(original) }),
      op(1, "DOWNLOAD", "new.txt", FILE(wanted), { blobId: "bl1", expected: { type: "ABSENT" } }),
    ], nextAfter: null })],
    ["POST /api/plans/p1/commit", () => json({ applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
    ["GET /api/spaces/s1/blobs/bl1/content", () => new Response(utf8("tampered bytes"))],
    ["POST /api/plans/p1/acknowledgements", () => json({})],
  ]);
  const { ctx } = context(h, root, store);
  const out = await runSpace(ctx, "sync");
  assert.equal(out.status, "partial");
  assert.equal(out.failures.length, 2);
  assert.deepEqual(Buffer.from(root.read("f.txt")), Buffer.from(original), "existing file untouched");
  assert.equal(root.has("new.txt"), false, "no empty placeholder left behind");
  assert.equal((await store.baselines("s1")).get("f.txt").sha256, sha(original), "old baseline kept");
});

test("a local edit made after the scan is never overwritten by a planned download", async () => {
  const root = new FakeDir("root");
  const planned = utf8("version the plan saw");
  root.put("f.txt", "edited by the user since");
  const store = new MemoryStore();
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan())],
    ["GET /api/plans/p1/operations", () => json({ operations: [op(0, "DOWNLOAD", "f.txt", FILE(utf8("server")), { blobId: "bl1", expected: FILE(planned) })], nextAfter: null })],
    ["POST /api/plans/p1/commit", () => json({ applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
    ["POST /api/plans/p1/acknowledgements", () => json({})],
  ]);
  const { ctx } = context(h, root, store);
  const out = await runSpace(ctx, "sync");
  assert.equal(out.failures.length, 1);
  assert.equal(h.calls.some((c) => c.path.includes("/blobs/")), false, "content was not even fetched");
  assert.equal(Buffer.from(root.read("f.txt")).toString(), "edited by the user since");
});

test("a stale plan is replanned instead of retried or forced", async () => {
  const root = new FakeDir("root");
  root.put("a.txt", "a");
  const store = new MemoryStore();
  let planCount = 0;
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan({ id: `p${++planCount}` }))],
    ["GET /api/plans/p\\d/operations", (c) => json({ operations: c.path.includes("p1") ? [op(0, "UPLOAD", "a.txt", FILE(utf8("a")))] : [op(0, "ADOPT", "a.txt", FILE(utf8("a")))], nextAfter: null })],
    ["POST /api/spaces/s1/uploads", () => json({ uploadId: "u1", state: "OPEN", size: 1, chunkSize: 4, chunkCount: 1, receivedChunks: [] }, 201)],
    ["PUT /api/uploads/u1/chunks/0", () => json({})],
    ["POST /api/uploads/u1/complete", () => json({})],
    ["POST /api/plans/p\\d/commit", (c) => c.path.includes("p1") ? err(409, "stale_plan", { replan: true }) : json({ applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
    ["POST /api/plans/p\\d/acknowledgements", () => json({})],
  ]);
  const { ctx } = context(h, root, store);
  const out = await runSpace(ctx, "sync");
  assert.equal(planCount, 2);
  assert.equal(h.calls.filter((c) => c.path === "/api/plans/p1/commit").length, 1, "the stale commit is not retried");
  assert.equal(out.done.adopted, 1);
  assert.equal(out.status, "completed");
});

test("a failed or incomplete scan reports baselines as unreadable, never absent", async () => {
  const root = new FakeDir("root");
  root.mkdir("locked").failListing = true;
  const store = new MemoryStore();
  await store.putBaselines("s1", [{ path: "locked/doc.txt", type: "FILE", sha256: sha(utf8("d")), size: 1 }]);
  const h = harness([
    ["GET /api/spaces/s1", () => json(SPACE)],
    ["POST /api/spaces/s1/plans", () => json(plan({ scanComplete: false }))],
    ["GET /api/plans/p1/operations", () => json({ operations: [op(0, "BLOCKED", "locked/doc.txt", null, { reason: "UNREADABLE" })], nextAfter: null })],
    ["POST /api/plans/p1/commit", () => json({ applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
  ]);
  const { ctx } = context(h, root, store);
  await runSpace(ctx, "sync");
  const planReq = h.calls.find((c) => c.path.endsWith("/plans"));
  assert.equal(planReq.body.scanComplete, false);
  const e = planReq.body.entries.find((x) => x.path === "locked/doc.txt");
  assert.equal(e.unreadable, true);
  assert.equal(e.local, undefined);
  assert.equal((await store.baselines("s1")).has("locked/doc.txt"), true, "baseline preserved");
});

test("a different server epoch discards local baselines instead of trusting them", async () => {
  const root = new FakeDir("root");
  const store = new MemoryStore();
  await store.putBaselines("s1", [{ path: "old.txt", type: "FILE", sha256: sha(utf8("o")), size: 1 }]);
  const h = harness([
    ["GET /api/spaces/s1", () => json({ ...SPACE, epoch: "E2" })],
    ["POST /api/spaces/s1/plans", () => json(plan({ epoch: "E2" }))],
    ["GET /api/plans/p1/operations", () => json({ operations: [], nextAfter: null })],
    ["POST /api/plans/p1/commit", () => json({ applied: [], conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false })],
  ]);
  const { ctx } = context(h, root, store);
  await runSpace(ctx, "sync");
  const planReq = h.calls.find((c) => c.path.endsWith("/plans"));
  assert.equal(planReq.body.epoch, "E2");
  assert.deepEqual(planReq.body.entries, [], "no baseline is sent, so no deletion can be inferred");
  assert.equal((await store.getBinding("s1"))?.epoch, "E2");
});

test("cancelling stops the run with a cancelled outcome", async () => {
  const root = new FakeDir("root");
  root.put("a.txt", "a");
  const ctl = new AbortController();
  const h = harness([["GET /api/spaces/s1", () => { ctl.abort(); return json(SPACE); }]]);
  const { ctx } = context(h, root, new MemoryStore(), { signal: ctl.signal });
  const out = await runSpace(ctx, "sync");
  assert.equal(out.status, "cancelled");
});
