import { Checks, startSampler, fmtBytes } from '../lib/util.js';
import { HashPool } from '../lib/hash-pool.js';

const SKIP_ALWAYS = new Set(['__harness_scratch__', '.fixture-manifest.json', '__links__']);

class Semaphore {
  constructor(n) { this.n = n; this.q = []; }
  async acquire() { if (this.n > 0) { this.n--; return; } await new Promise((r) => this.q.push(r)); }
  release() { const r = this.q.shift(); if (r) r(); else this.n++; }
}

/** Enumerate (pruning) and hash in a pipeline. Returns metrics and per-file hashes. */
async function scan(root, { pruned, poolSize, chunkSize, mode, enumConcurrency, hash = true, cancelAfterMs = 0 }) {
  const pool = hash ? new HashPool(poolSize) : null;
  const sampler = startSampler({ intervalMs: 25 });
  const t0 = performance.now();
  const m = { files: 0, dirs: 0, prunedDirs: 0, bytes: 0, timeToFirstEntryMs: null, timeToFirstProgressMs: null, enumerationDoneMs: null, hashedFiles: 0, errors: [] };
  const hashes = new Map();
  const inflight = new Semaphore(poolSize * 4);
  const pending = new Set();
  const dirQueue = [{ h: root, p: '' }];
  let active = 0, cancelled = false, cancelLatencyMs = null;
  let cancelPromise = null, finished = false;
  if (cancelAfterMs) setTimeout(() => {
    if (finished) return;
    cancelled = true;
    const c0 = performance.now();
    cancelPromise = pool.cancelAll().then(() => { cancelLatencyMs = performance.now() - c0; });
  }, cancelAfterMs);

  const handleDir = async ({ h, p }) => {
    m.dirs++;
    for await (const [name, child] of h.entries()) {
      if (cancelled) return;
      if (m.timeToFirstEntryMs === null) m.timeToFirstEntryMs = performance.now() - t0;
      if (!p && SKIP_ALWAYS.has(name)) continue;
      if (child.kind === 'directory') {
        if (pruned.has(name)) { m.prunedDirs++; continue; }
        dirQueue.push({ h: child, p: `${p}${name}/` });
      } else {
        const path = p + name;
        if (name === '.git') continue; // a .git file (worktree) is always excluded
        if (!hash) { m.files++; continue; }
        await inflight.acquire();
        if (cancelled) { inflight.release(); return; }
        const job = (async () => {
          try {
            const file = await child.getFile();
            m.files++; m.bytes += file.size;
            const r = await pool.hash(file, { mode, chunkSize });
            if (m.timeToFirstProgressMs === null) m.timeToFirstProgressMs = performance.now() - t0;
            m.hashedFiles++;
            hashes.set(path, r.hash);
          } catch (e) { if (!e.cancelled) m.errors.push(`${path}: ${e.message}`); }
          finally { inflight.release(); }
        })();
        pending.add(job);
        job.finally(() => pending.delete(job));
      }
    }
  };
  await Promise.all(Array.from({ length: enumConcurrency }, async () => {
    for (;;) {
      if (cancelled) return;
      const d = dirQueue.pop();
      if (!d) { if (active === 0) return; await new Promise((r) => setTimeout(r, 1)); continue; }
      active++;
      try { await handleDir(d); } catch (e) { m.errors.push(`dir ${d.p}: ${e.name}: ${e.message}`); } finally { active--; }
    }
  }));
  m.enumerationDoneMs = performance.now() - t0;
  await Promise.allSettled([...pending]);
  m.totalMs = performance.now() - t0;
  finished = true;
  await cancelPromise;
  pool?.terminate();
  Object.assign(m, sampler.stop(), { cancelLatencyMs });
  m.filesPerSec = Math.round(m.files / (m.totalMs / 1000));
  m.mibPerSec = Math.round(m.bytes / (1 << 20) / (m.totalMs / 1000));
  return { m, hashes };
}

export default {
  id: 'perf',
  title: 'Enumeration + hashing performance on a generated fixture',
  needsRoot: true,
  spec: '§8 benchmark, §15; produces the numeric budgets',
  hint: 'Select a fixture root created by fixtures/generate.mjs. Run 2–3 times (first run is cold) and record browser/OS in the scenario label. Large-fixture runs take minutes.',
  options: [
    { key: 'pruned', label: 'Pruned dir names', default: 'node_modules,build,target,.gradle,.git,dist', type: 'text' },
    { key: 'poolSize', label: 'Hash workers', default: 4 },
    { key: 'chunkMiB', label: 'Read chunk (MiB)', default: 1 },
    { key: 'mode', label: 'Read mode (slice|stream)', default: 'slice', type: 'text' },
    { key: 'enumConcurrency', label: 'Parallel directory listings', default: 1 },
    { key: 'cancelTest', label: 'Also measure cancellation latency', default: true, type: 'checkbox' },
    { key: 'cancelAfterMs', label: 'Cancel after (ms)', default: 400 },
  ],
  async run(ctx) {
    const c = new Checks();
    const o = ctx.opts;
    const pruned = new Set(String(o.pruned).split(',').map((s) => s.trim()).filter(Boolean));
    const cfg = { pruned, poolSize: Number(o.poolSize), chunkSize: Number(o.chunkMiB) * (1 << 20), mode: o.mode, enumConcurrency: Number(o.enumConcurrency) };
    let manifest = null;
    try { manifest = JSON.parse(await (await (await ctx.root.getFileHandle('.fixture-manifest.json')).getFile()).text()); } catch {}
    c.note('fixture manifest', manifest ? manifest.counts : 'not found: correctness comparison skipped');

    const names = await scan(ctx.root, { ...cfg, hash: false });
    c.note('names-only enumeration', { files: names.m.files, dirs: names.m.dirs, prunedDirs: names.m.prunedDirs, ms: Math.round(names.m.enumerationDoneMs), timeToFirstEntryMs: Math.round(names.m.timeToFirstEntryMs ?? 0) });

    const full = await scan(ctx.root, cfg);
    const m = full.m;
    const data = { config: { ...cfg, pruned: [...pruned] }, namesOnly: names.m, full: m };
    c.note('full run (enumerate + getFile + hash)', { files: m.files, bytes: fmtBytes(m.bytes), enumerationMs: Math.round(m.enumerationDoneMs), totalMs: Math.round(m.totalMs), timeToFirstProgressMs: Math.round(m.timeToFirstProgressMs ?? 0), filesPerSec: m.filesPerSec, mibPerSec: m.mibPerSec, heapPeakGrowth: m.heapPeakGrowthBytes == null ? 'n/a' : fmtBytes(m.heapPeakGrowthBytes), maxMainThreadLagMs: m.maxMainThreadLagMs });
    c.check('no read/enumeration errors', m.errors.length === 0, m.errors.slice(0, 3).join(' | '));

    if (manifest) {
      const expected = new Map(manifest.files.map((f) => [f.path, f.hash ?? f.sha256]));
      for (const b of manifest.boundary ?? []) expected.set(b.path, b.sha256);
      let mismatch = 0, missing = 0, extra = 0;
      for (const [p, h] of expected) { if (!full.hashes.has(p)) missing++; else if (full.hashes.get(p) !== h) mismatch++; }
      for (const p of full.hashes.keys()) if (!expected.has(p)) extra++;
      c.check('every included file hashed, hash equals generator manifest', mismatch === 0 && missing === 0 && extra === 0, JSON.stringify({ expected: expected.size, hashed: full.hashes.size, mismatch, missing, extra }));
      c.check('excluded trees were pruned, never hashed (hashed count excludes them)', full.hashes.size <= expected.size && m.prunedDirs > 0, `prunedDirs=${m.prunedDirs}, excludedFilesInFixture=${manifest.counts.excludedFiles}`);
    }
    if (m.hashedFiles > 0) {
      c.note('time to first progress (budget input)', `${Math.round(m.timeToFirstProgressMs)} ms`);
    }

    if (o.cancelTest) {
      const cancel = await scan(ctx.root, { ...cfg, cancelAfterMs: Number(o.cancelAfterMs) });
      data.cancel = cancel.m;
      const fired = cancel.m.cancelLatencyMs != null;
      c.note(`cancel at ${o.cancelAfterMs} ms`, { cancelLatencyMs: fired ? Math.round(cancel.m.cancelLatencyMs) : 'run finished before cancel: use a larger fixture or smaller delay', filesHashedBeforeCancel: cancel.m.hashedFiles, totalMs: Math.round(cancel.m.totalMs) });
      if (fired) c.check('cancellation returns all workers to idle promptly (< 500 ms)', cancel.m.cancelLatencyMs < 500, `${Math.round(cancel.m.cancelLatencyMs)} ms`);
      c.check('cancelled run produced no errors', cancel.m.errors.length === 0, cancel.m.errors.slice(0, 2).join(' | '));
    }
    return { checks: c.items, status: c.status, data };
  },
};
