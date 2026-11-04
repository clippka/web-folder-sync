// Step 8 safety gate, browser side: deletions run only for confirmed paths that a fresh plan still proposes, and every
// local removal is rechecked against the reviewed bytes. Failures keep baselines so nothing turns into a new deletion.
import { test } from "node:test";
import assert from "node:assert/strict";
import { load, FakeDir, CountingHasher, sha, utf8 } from "./helpers.mjs";

const { Api, MemoryStore, runSpace } = await load("test/entry.ts");

const LIMITS = { maxFileSizeBytes: 100 * 1024 * 1024, maxIncludedFiles: 25000, chunkSizeBytes: 4, maxManifestBatch: 1000, deletionEnabled: true };
const space = (limits = {}) => ({ id: "s1", name: "S", revision: 1, policyRevision: 1, epoch: "E1", limits: { ...LIMITS, ...limits } });
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const plan = () => ({ id: "p1", state: "READY", phase: "CONTENT", serverRevision: 1, policyRevision: 1, epoch: "E1", paused: false, rescanRequired: false, scanComplete: true, counts: {}, decisionCount: 0, rejected: [], rejectedCount: 0, leaseId: null });
const op = (seq, kind, path, state, expected) => ({ seq, kind, path, needsDecision: true, state, expected, reason: null, cause: null, blobId: null });
const FILE = (b) => ({ type: "FILE", sha256: sha(b), size: b.length });
const DIR = { type: "DIRECTORY" };
const committed = (applied) => ({ applied: applied.map((path) => ({ path, kind: "DELETE", version: 2 })), conflicts: [], skipped: [], pendingDecisions: [], policyChanged: false, rescanRequired: false });

function setup({ ops, root, baselines = [], limits = {}, applied = [], ackFails = false }) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const u = new URL(url, "http://x");
    const method = init.method ?? "GET";
    const body = typeof init.body === "string" ? JSON.parse(init.body) : init.body;
    calls.push({ method, path: u.pathname, body });
    if (method === "GET" && u.pathname === "/api/spaces/s1") return json(space(limits));
    if (method === "POST" && u.pathname === "/api/spaces/s1/plans") return json(plan());
    if (u.pathname === "/api/plans/p1/operations") return json({ operations: ops, nextAfter: null });
    if (u.pathname === "/api/plans/p1/commit") return json(committed(applied));
    if (u.pathname === "/api/plans/p1/acknowledgements") return ackFails ? json({ code: "x", message: "boom", retryable: false, details: {} }, 500) : json({});
    throw new Error(`unrouted ${method} ${u.pathname}`);
  };
  const store = new MemoryStore();
  const binding = { spaceId: "s1", bindingId: "b1", deviceId: "d1", handle: root, rootName: "root", enrollmentGeneration: 1, epoch: "E1", connectedAt: 0 };
  const events = [];
  const ctx = { api: new Api(fetchImpl, () => "csrf"), store, hasher: new CountingHasher(), root, binding, signal: new AbortController().signal, ui: { phase() {}, progress() {}, file: (p, s, d) => events.push([p, s, d]) } };
  return { ctx, store, calls, events, seed: () => store.putBaselines("s1", baselines) };
}
const commitBody = (t) => t.calls.find((c) => c.path.endsWith("/commit"))?.body;
const acks = (t) => t.calls.filter((c) => c.path.endsWith("/acknowledgements")).flatMap((c) => c.body.entries);

test("without confirmation nothing is approved, even if the plan proposes deletions", async () => {
  const v = utf8("x");
  const root = new FakeDir("root"); root.put("gone-here.txt", "x");
  const t = setup({ root, ops: [op(0, "PROPOSE_LOCAL_DELETION", "gone-here.txt", { type: "ABSENT" }, FILE(v))], baselines: [{ path: "gone-here.txt", type: "FILE", sha256: sha(v), size: 1 }] });
  await t.seed();
  const out = await runSpace(t.ctx, "sync");
  assert.deepEqual(commitBody(t).approvedLocalDeletions, []);
  assert.deepEqual(commitBody(t).approvedServerDeletions, []);
  assert.ok(root.has("gone-here.txt"));
  assert.equal(out.status, "needs-review");
  assert.equal(out.deletions.fileCount, 1);
});

test("a confirmed server deletion is sent for approval and its baseline removed once applied", async () => {
  const v = utf8("x");
  const root = new FakeDir("root");
  const t = setup({ root, applied: ["a.txt"], ops: [op(0, "PROPOSE_SERVER_DELETION", "a.txt", { type: "ABSENT" }, FILE(v))], baselines: [{ path: "a.txt", type: "FILE", sha256: sha(v), size: 1 }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["a.txt"]);
  const out = await runSpace(t.ctx, "sync");
  assert.deepEqual(commitBody(t).approvedServerDeletions, ["a.txt"]);
  assert.equal(out.done.deleted, 1);
  assert.equal((await t.store.baselines("s1")).has("a.txt"), false);
  assert.deepEqual(acks(t), [{ path: "a.txt", outcome: "APPLIED" }]);
});

test("the gate being closed on the server means no approval is ever sent", async () => {
  const v = utf8("x");
  const root = new FakeDir("root");
  const t = setup({ root, limits: { deletionEnabled: false }, ops: [op(0, "PROPOSE_SERVER_DELETION", "a.txt", { type: "ABSENT" }, FILE(v))], baselines: [{ path: "a.txt", type: "FILE", sha256: sha(v), size: 1 }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["a.txt"]);
  await runSpace(t.ctx, "sync");
  assert.deepEqual(commitBody(t).approvedServerDeletions, []);
  assert.equal((await t.store.baselines("s1")).has("a.txt"), true);
});

test("a stale approval for a path the fresh plan no longer proposes authorizes nothing", async () => {
  const root = new FakeDir("root"); root.put("other.txt", "keep me");
  const t = setup({ root, ops: [op(0, "PROPOSE_LOCAL_DELETION", "new-proposal.txt", { type: "ABSENT" }, FILE(utf8("n")))], baselines: [] });
  t.ctx.approvedDeletions = new Set(["old-approval.txt"]);
  await runSpace(t.ctx, "sync");
  assert.deepEqual(commitBody(t).approvedLocalDeletions, []);
  assert.deepEqual(commitBody(t).approvedServerDeletions, []);
  assert.ok(root.has("other.txt"));
});

test("a confirmed local deletion removes only the reviewed bytes, then drops the baseline", async () => {
  const v = utf8("reviewed");
  const root = new FakeDir("root"); root.put("f.txt", v);
  const t = setup({ root, ops: [op(0, "PROPOSE_LOCAL_DELETION", "f.txt", { type: "ABSENT" }, FILE(v))], baselines: [{ path: "f.txt", type: "FILE", sha256: sha(v), size: v.length }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["f.txt"]);
  const out = await runSpace(t.ctx, "sync");
  assert.equal(root.has("f.txt"), false);
  assert.equal(out.done.deleted, 1);
  assert.equal((await t.store.baselines("s1")).has("f.txt"), false);
  assert.deepEqual(commitBody(t).approvedLocalDeletions, ["f.txt"]);
});

test("an external edit after the review keeps the file and the baseline", async () => {
  const reviewed = utf8("reviewed");
  const root = new FakeDir("root"); root.put("f.txt", "edited in another program meanwhile");
  const t = setup({ root, ops: [op(0, "PROPOSE_LOCAL_DELETION", "f.txt", { type: "ABSENT" }, FILE(reviewed))], baselines: [{ path: "f.txt", type: "FILE", sha256: sha(reviewed), size: reviewed.length }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["f.txt"]);
  const out = await runSpace(t.ctx, "sync");
  assert.ok(root.has("f.txt"));
  assert.equal(Buffer.from(root.read("f.txt")).toString(), "edited in another program meanwhile");
  assert.equal(out.done.deleted, 0);
  assert.equal(out.failures.length, 1);
  assert.equal((await t.store.baselines("s1")).get("f.txt").sha256, sha(reviewed));
  assert.deepEqual(acks(t), []);
});

test("files are removed before their folders, and a folder with extra content is kept with its baseline", async () => {
  const v = utf8("x");
  const root = new FakeDir("root");
  root.put("d/x.txt", v); root.put("d/inner/y.txt", v); root.put("keep/z.txt", v);
  root.put("keep/ignored.log", "ignored by rules");           // unexpected child: not part of the plan
  const ops = [
    op(0, "PROPOSE_LOCAL_DELETION", "d/inner", { type: "ABSENT" }, DIR),
    op(1, "PROPOSE_LOCAL_DELETION", "d", { type: "ABSENT" }, DIR),
    op(2, "PROPOSE_LOCAL_DELETION", "d/x.txt", { type: "ABSENT" }, FILE(v)),
    op(3, "PROPOSE_LOCAL_DELETION", "d/inner/y.txt", { type: "ABSENT" }, FILE(v)),
    op(4, "PROPOSE_LOCAL_DELETION", "keep/z.txt", { type: "ABSENT" }, FILE(v)),
    op(5, "PROPOSE_LOCAL_DELETION", "keep", { type: "ABSENT" }, DIR),
  ];
  const baselines = [
    { path: "d", type: "DIRECTORY" }, { path: "d/inner", type: "DIRECTORY" }, { path: "keep", type: "DIRECTORY" },
    { path: "d/x.txt", type: "FILE", sha256: sha(v), size: 1 }, { path: "d/inner/y.txt", type: "FILE", sha256: sha(v), size: 1 }, { path: "keep/z.txt", type: "FILE", sha256: sha(v), size: 1 },
  ];
  const t = setup({ root, ops, baselines });
  await t.seed();
  t.ctx.approvedDeletions = new Set(ops.map((o) => o.path));
  const out = await runSpace(t.ctx, "sync");
  assert.equal(root.has("d"), false, "emptied folders are removed");
  assert.equal(root.has("keep/z.txt"), false);
  assert.ok(root.has("keep/ignored.log"), "unexpected content is untouched");
  assert.ok(root.has("keep"));
  const done = t.events.filter(([, s]) => s === "done").map(([p]) => p);
  assert.ok(done.indexOf("d/inner/y.txt") < done.indexOf("d/inner") && done.indexOf("d/inner") < done.indexOf("d"), "deepest first");
  const base = await t.store.baselines("s1");
  assert.equal(base.has("keep"), true, "blocked deletion keeps its baseline");
  assert.equal(base.has("d"), false);
  assert.equal(out.failures.length, 1);
  assert.match(out.failures[0].message, /still contains/);
});

test("permission loss while deleting leaves the file, the baseline and no acknowledgement", async () => {
  const v = utf8("x");
  const root = new FakeDir("root"); root.put("f.txt", v);
  root.removeEntry = async () => { throw Object.assign(new Error("denied"), { name: "NotAllowedError" }); };
  const t = setup({ root, ops: [op(0, "PROPOSE_LOCAL_DELETION", "f.txt", { type: "ABSENT" }, FILE(v))], baselines: [{ path: "f.txt", type: "FILE", sha256: sha(v), size: 1 }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["f.txt"]);
  const out = await runSpace(t.ctx, "sync");
  assert.ok(root.has("f.txt"));
  assert.equal(out.failures.length, 1);
  assert.equal((await t.store.baselines("s1")).has("f.txt"), true);
  assert.deepEqual(acks(t), []);
});

test("a lost acknowledgement does not undo or repeat a deletion; local records are already saved", async () => {
  const v = utf8("x");
  const root = new FakeDir("root"); root.put("f.txt", v);
  const t = setup({ root, ackFails: true, ops: [op(0, "PROPOSE_LOCAL_DELETION", "f.txt", { type: "ABSENT" }, FILE(v))], baselines: [{ path: "f.txt", type: "FILE", sha256: sha(v), size: 1 }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["f.txt"]);
  const out = await runSpace(t.ctx, "sync");
  assert.equal(root.has("f.txt"), false);
  assert.equal((await t.store.baselines("s1")).has("f.txt"), false);
  assert.ok(out.notices.some((n) => /Could not record/.test(n)));
});

test("an incomplete scan approves nothing, whatever was confirmed", async () => {
  const v = utf8("x");
  const root = new FakeDir("root"); root.mkdir("locked").failListing = true;
  const t = setup({ root, ops: [], baselines: [{ path: "locked/doc.txt", type: "FILE", sha256: sha(v), size: 1 }] });
  await t.seed();
  t.ctx.approvedDeletions = new Set(["locked/doc.txt"]);
  await runSpace(t.ctx, "sync");
  const planReq = t.calls.find((c) => c.path.endsWith("/plans"));
  assert.equal(planReq.body.scanComplete, false);
  assert.deepEqual(commitBody(t).approvedServerDeletions, []);
  assert.deepEqual(commitBody(t).approvedLocalDeletions, []);
});
