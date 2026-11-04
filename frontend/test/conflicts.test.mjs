// Conflict decisions applied to a local folder: the safety checks happen before anything is recorded or written.
import { test } from "node:test";
import assert from "node:assert/strict";
import { load, FakeDir, CountingHasher, sha, utf8 } from "./helpers.mjs";

const { Api, MemoryStore, resolveConflict, optionLabel, isDeletion, reviewDeletions } = await load("test/entry.ts");

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const FILE = (bytes, blobId) => ({ type: "FILE", sha256: sha(bytes), size: bytes.length, blobId });

function conflict(over = {}) {
  const local = utf8("my edit"), server = utf8("their edit");
  return {
    id: "c1", spaceId: "s1", path: "doc.txt", kind: "CONTENT", state: "UNRESOLVED", resolution: null, deviceLabel: "Laptop", serverEntryVersion: 3,
    local: FILE(local, "blobL"), server: FILE(server, "blobS"), createdAt: 1, resolvedAt: null, serverUpdatedAt: 2,
    options: ["KEEP_LOCAL", "KEEP_SERVER", "KEEP_BOTH"], bindingId: "b1", ...over,
  };
}

function setup(routes, localContent = "my edit") {
  const root = new FakeDir("root");
  if (localContent !== null) root.put("doc.txt", localContent, 500);
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const u = new URL(url, "http://x");
    const method = init.method ?? "GET";
    const body = typeof init.body === "string" ? JSON.parse(init.body) : init.body;
    calls.push({ method, path: u.pathname, body, headers: init.headers });
    for (const [key, handler] of Object.entries(routes)) {
      const [m, p] = key.split(" ");
      if (m === method && p === u.pathname) return handler({ body });
    }
    throw new Error(`unrouted ${method} ${u.pathname}`);
  };
  const store = new MemoryStore();
  const binding = { spaceId: "s1", bindingId: "b1", deviceId: "d1", handle: root, rootName: "root", enrollmentGeneration: 1, epoch: "E1", connectedAt: 0 };
  const ctx = { api: new Api(fetchImpl, () => "csrf"), store, hasher: new CountingHasher(), root, binding, signal: new AbortController().signal, ui: { phase() {}, progress() {}, file() {} } };
  return { root, calls, store, ctx };
}

const resolved = (extra = {}) => json({ conflict: conflict({ state: "RESOLVED" }), entryVersion: 3, revision: 9, siblingPath: null, siblingVersion: null, ...extra });
const read = (root, p) => Buffer.from(root.read(p));
const posted = (calls) => calls.filter((c) => c.method === "POST");

test("keep server replaces the folder's file with verified server bytes and records a baseline", async () => {
  const serverBytes = utf8("their edit");
  const t = setup({
    "POST /api/conflicts/c1/resolve": () => resolved(),
    "GET /api/spaces/s1/blobs/blobS/content": () => new Response(serverBytes),
  });
  const r = await resolveConflict(t.ctx, conflict(), "KEEP_SERVER");
  assert.equal(r.ok, true, r.message);
  assert.deepEqual(read(t.root, "doc.txt"), Buffer.from(serverBytes));
  assert.deepEqual(posted(t.calls)[0].body, { resolution: "KEEP_SERVER", expectedServerVersion: 3 });
  assert.ok(posted(t.calls)[0].headers["Idempotency-Key"]);
  assert.equal((await t.store.baselines("s1")).get("doc.txt").sha256, sha(serverBytes));
});

test("keep server refuses to overwrite a file edited after the conflict was recorded, and records nothing", async () => {
  const t = setup({ "POST /api/conflicts/c1/resolve": () => resolved() }, "edited yet again");
  const r = await resolveConflict(t.ctx, conflict(), "KEEP_SERVER");
  assert.equal(r.ok, false);
  assert.equal(r.recorded, false);
  assert.equal(posted(t.calls).length, 0, "no decision reached the server");
  assert.deepEqual(read(t.root, "doc.txt"), Buffer.from(utf8("edited yet again")));
});

test("keep both saves the local version beside the original and replaces the original with the server's", async () => {
  const local = utf8("my edit"), server = utf8("their edit");
  const sibling = "doc (conflict Laptop 2026-11-04).txt";
  const t = setup({
    "POST /api/conflicts/c1/resolve": () => resolved({ siblingPath: sibling, siblingVersion: 1 }),
    "GET /api/spaces/s1/blobs/blobL/content": () => new Response(local),
    "GET /api/spaces/s1/blobs/blobS/content": () => new Response(server),
  });
  const r = await resolveConflict(t.ctx, conflict(), "KEEP_BOTH");
  assert.equal(r.ok, true, r.message);
  assert.deepEqual(read(t.root, sibling), Buffer.from(local));
  assert.deepEqual(read(t.root, "doc.txt"), Buffer.from(server));
  const base = await t.store.baselines("s1");
  assert.equal(base.get(sibling).sha256, sha(local));
  assert.equal(base.get("doc.txt").sha256, sha(server));
  assert.match(r.message, /doc \(conflict Laptop/);
});

test("a failed local write after the decision leaves the folder untouched and says what happened", async () => {
  const t = setup({
    "POST /api/conflicts/c1/resolve": () => resolved(),
    "GET /api/spaces/s1/blobs/blobS/content": () => new Response(utf8("corrupted in transit")),
  });
  const r = await resolveConflict(t.ctx, conflict(), "KEEP_SERVER");
  assert.equal(r.ok, false);
  assert.equal(r.recorded, true);
  assert.match(r.message, /recorded on the server/);
  assert.deepEqual(read(t.root, "doc.txt"), Buffer.from(utf8("my edit")), "the original file survived");
  assert.equal((await t.store.baselines("s1")).has("doc.txt"), false, "no baseline for unverified bytes");
});

test("keep this folder's version records the candidate as the baseline and changes no local file", async () => {
  const t = setup({ "POST /api/conflicts/c1/resolve": () => resolved() });
  const r = await resolveConflict(t.ctx, conflict(), "KEEP_LOCAL");
  assert.equal(r.ok, true, r.message);
  assert.deepEqual(read(t.root, "doc.txt"), Buffer.from(utf8("my edit")));
  assert.equal((await t.store.baselines("s1")).get("doc.txt").sha256, sha(utf8("my edit")));
});

test("a conflict found by another folder cannot be decided here with different content", async () => {
  const t = setup({ "POST /api/conflicts/c1/resolve": () => resolved() }, "something else entirely");
  const r = await resolveConflict(t.ctx, conflict({ bindingId: "someone-else" }), "KEEP_LOCAL");
  assert.equal(r.ok, false);
  assert.equal(posted(t.calls).length, 0);
});

test("a stale decision asks for a refresh and changes nothing", async () => {
  const t = setup({ "POST /api/conflicts/c1/resolve": () => json({ code: "stale_conflict", message: "changed", retryable: true, details: { refresh: true } }, 409) });
  const r = await resolveConflict(t.ctx, conflict(), "KEEP_SERVER");
  assert.equal(r.ok, false);
  assert.equal(r.refresh, true);
  assert.deepEqual(read(t.root, "doc.txt"), Buffer.from(utf8("my edit")));
});

test("a refused deletion changes nothing locally", async () => {
  const c = conflict({ kind: "DELETE_VS_EDIT", local: { type: "ABSENT" }, options: ["KEEP_LOCAL", "KEEP_SERVER"] });
  const t = setup({ "POST /api/conflicts/c1/resolve": () => json({ code: "deletion_disabled", message: "off", retryable: false, details: {} }, 409) }, null);
  const r = await resolveConflict(t.ctx, c, "KEEP_LOCAL");
  assert.equal(r.ok, false);
  assert.match(r.message, /not enabled/);
  assert.equal(t.root.has("doc.txt"), false);
});

test("delete-versus-edit options are worded as keep or confirm, never as local/server", () => {
  const deletedHere = conflict({ kind: "DELETE_VS_EDIT", local: { type: "ABSENT" }, options: ["KEEP_LOCAL", "KEEP_SERVER"] });
  assert.equal(optionLabel(deletedHere, "KEEP_SERVER").label, "Keep modified file");
  assert.equal(optionLabel(deletedHere, "KEEP_LOCAL").label, "Confirm deletion");
  assert.equal(isDeletion(deletedHere, "KEEP_LOCAL"), true);
  assert.equal(isDeletion(deletedHere, "KEEP_SERVER"), false);
  const deletedThere = conflict({ kind: "DELETE_VS_EDIT", server: { type: "ABSENT" }, options: ["KEEP_LOCAL", "KEEP_SERVER"] });
  assert.equal(optionLabel(deletedThere, "KEEP_LOCAL").label, "Keep modified file");
  assert.equal(optionLabel(deletedThere, "KEEP_SERVER").label, "Confirm deletion");
  assert.equal(isDeletion(deletedThere, "KEEP_SERVER"), true);
  assert.equal(isDeletion(conflict(), "KEEP_SERVER"), false);
});

// ---- deletion review -------------------------------------------------------------------------------------------------

const del = (n, kind = "PROPOSE_SERVER_DELETION", type = "FILE") =>
  Array.from({ length: n }, (_, i) => ({ seq: i, kind, path: `f${i}.txt`, needsDecision: true, state: null, expected: { type }, reason: null, cause: null, blobId: null }));
const limits = { deletionEnabled: false, deletionWarnCount: 100, deletionWarnPercent: 20 };

test("a deletion review lists direction and count, and stays quiet for small batches", () => {
  assert.equal(reviewDeletions([], { activeFiles: 10, limits }), null);
  const r = reviewDeletions([...del(2), ...del(1, "PROPOSE_LOCAL_DELETION")], { activeFiles: 1000, limits });
  assert.equal(r.fromServer.length, 2);
  assert.equal(r.fromThisFolder.length, 1);
  assert.equal(r.fileCount, 3);
  assert.equal(r.emphasized, false);
  assert.equal(r.executionEnabled, false);
});

test("batches of 100 files or more than 20% of the active files are emphasized, directories do not count as files", () => {
  assert.equal(reviewDeletions(del(100), { activeFiles: 100000, limits }).emphasized, true);
  assert.equal(reviewDeletions(del(99), { activeFiles: 100000, limits }).emphasized, false);
  assert.equal(reviewDeletions(del(21), { activeFiles: 100, limits }).emphasized, true, "21 of 100 is more than 20%");
  assert.equal(reviewDeletions(del(20), { activeFiles: 100, limits }).emphasized, false, "exactly 20% is not more than 20%");
  const dirs = reviewDeletions(del(150, "PROPOSE_SERVER_DELETION", "DIRECTORY"), { activeFiles: 100000, limits });
  assert.equal(dirs.fileCount, 0);
  assert.equal(dirs.directoryCount, 150);
  assert.equal(dirs.emphasized, false);
  assert.equal(reviewDeletions(del(30), { activeFiles: 100, limits: { ...limits, deletionWarnPercent: 50, deletionWarnCount: 25 } }).emphasized, true, "thresholds are configurable");
});
