import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";

async function load(entry) {
  const out = await build({ entryPoints: [entry], bundle: true, format: "esm", write: false });
  return import("data:text/javascript;base64," + Buffer.from(out.outputFiles[0].text).toString("base64"));
}
const base = { supported: true, connected: true, needsPermission: false, unreachable: false, running: false, offline: false, outcome: null, cancelled: false, pending: false, openConflicts: 0, lastScanAt: null };
const fmt = () => "T";

test("every specified status can be derived", async () => {
  const { deriveStatus } = await load("src/ui/state.ts");
  const label = (o) => deriveStatus({ ...base, ...o }, fmt).label;
  assert.equal(label({ connected: false }), "Not connected");
  assert.equal(label({ supported: false, connected: false }), "Not connected");
  assert.equal(label({ needsPermission: true }), "Permission required");
  assert.equal(label({}), "Ready");
  assert.equal(label({ running: true, runMode: "scan" }), "Scanning");
  assert.equal(label({ running: true, runMode: "sync" }), "Syncing");
  assert.equal(label({ outcome: "needs-review" }), "Needs review");
  assert.equal(label({ outcome: "completed", lastScanAt: 5, openConflicts: 2 }), "Conflicts");
  assert.equal(label({ outcome: "completed", lastScanAt: 5 }), "Up to date");
  assert.equal(label({ cancelled: true }), "Paused");
  assert.equal(label({ pending: true }), "Paused");
  assert.equal(label({ offline: true }), "Offline");
  assert.equal(label({ outcome: "failed" }), "Failed");
  assert.equal(label({ unreachable: true }), "Failed");
});

test("Up to date always shows the last scan time", async () => {
  const { deriveStatus } = await load("src/ui/state.ts");
  const s = deriveStatus({ ...base, outcome: "up-to-date", lastScanAt: 1234 }, (ms) => `at ${ms}`);
  assert.equal(s.text, "Up to date (last scan at 1234)");
  // Without a recorded scan time it must not claim to be up to date.
  assert.notEqual(deriveStatus({ ...base, outcome: "up-to-date", lastScanAt: null }, fmt).label, "Up to date");
});

test("permission, running and offline take precedence in a sensible order", async () => {
  const { deriveStatus } = await load("src/ui/state.ts");
  assert.equal(deriveStatus({ ...base, needsPermission: true, offline: true, openConflicts: 3 }, fmt).label, "Permission required");
  assert.equal(deriveStatus({ ...base, running: true, runMode: "sync", offline: true }, fmt).label, "Syncing");
  assert.equal(deriveStatus({ ...base, offline: true, openConflicts: 3 }, fmt).label, "Offline");
});

function fakeDeps() {
  const timers = [];
  const streams = [];
  const deps = {
    revision: 0,
    openStream: () => {
      const s = { errorCb: null, revCb: null, closed: false, close() { this.closed = true; }, onRevision(cb) { this.revCb = cb; }, onError(cb) { this.errorCb = cb; }, emit(_t, data) { this.revCb?.(data); } };
      streams.push(s);
      return s;
    },
    fetchRevision: async () => deps.revision,
    setTimer: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimer: () => {},
  };
  return { timers, streams, deps };
}

test("a newer revision from the stream is reported once, and nothing else happens", async () => {
  const { ChangeWatcher } = await load("src/ui/changes.ts");
  const f = fakeDeps();
  const seen = [];
  const w = new ChangeWatcher("/api/spaces/x/events", 5, f.deps, (r) => seen.push(r));
  w.start();
  f.streams[0].emit("revision", "5");
  assert.deepEqual(seen, []);
  f.streams[0].emit("revision", "6");
  f.streams[0].emit("revision", "6");
  assert.deepEqual(seen, [6]);
  w.acknowledge(9);
  f.streams[0].emit("revision", "8");
  assert.deepEqual(seen, [6]);
  w.stop();
  assert.ok(f.streams[0].closed);
});

test("a failing stream falls back to slow polling", async () => {
  const { ChangeWatcher, POLL_MS } = await load("src/ui/changes.ts");
  const f = fakeDeps();
  const seen = [];
  const w = new ChangeWatcher("/e", 1, f.deps, (r) => seen.push(r));
  w.start();
  f.streams[0].errorCb();
  assert.equal(w.mode, "polling");
  assert.equal(f.timers.length, 1);
  assert.equal(f.timers[0].ms, POLL_MS);
  f.deps.revision = 4;
  await f.timers[0].fn();
  assert.deepEqual(seen, [4]);
  assert.equal(f.timers.length, 2, "keeps polling");
  w.stop();
  await f.timers[1].fn();
  assert.equal(f.timers.length, 2, "no polling after stop");
});
