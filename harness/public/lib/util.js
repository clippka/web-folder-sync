export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function fmtBytes(n) {
  if (n == null) return '–';
  const u = ['B', 'KiB', 'MiB', 'GiB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
}

export const ms = (n) => `${n.toFixed(0)} ms`;

/** Collects named boolean checks; status is pass only if all pass. */
export class Checks {
  constructor() { this.items = []; }
  check(name, ok, detail) { this.items.push({ name, ok: !!ok, detail: detail === undefined ? undefined : String(detail) }); return !!ok; }
  /** Record an observation with no expected value. */
  note(name, value) { this.items.push({ name, ok: null, detail: typeof value === 'string' ? value : JSON.stringify(value) }); }
  get status() { return this.items.some((i) => i.ok === false) ? 'fail' : this.items.some((i) => i.ok === true) ? 'pass' : 'info'; }
}

export async function attempt(fn) {
  try { return { ok: true, value: await fn() }; }
  catch (e) { return { ok: false, error: `${e.name}: ${e.message}` , name: e.name }; }
}

export const SCRATCH = '__harness_scratch__';

/** Fresh empty scratch directory below the selected root. All mutating tests stay inside it. */
export async function freshScratch(root) {
  try { await root.removeEntry(SCRATCH, { recursive: true }); } catch (e) { if (e.name !== 'NotFoundError') throw e; }
  return root.getDirectoryHandle(SCRATCH, { create: true });
}

export async function writeBytes(dir, name, data) {
  const fh = await dir.getFileHandle(name, { create: true });
  const w = await fh.createWritable();
  await w.write(data);
  await w.close();
  return fh;
}

export async function readBytes(dir, name) {
  const fh = await dir.getFileHandle(name);
  return new Uint8Array(await (await fh.getFile()).arrayBuffer());
}

export const bytesEqual = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

export async function listNames(dir) {
  const out = [];
  for await (const [name, h] of dir.entries()) out.push(`${name}${h.kind === 'directory' ? '/' : ''}`);
  return out.sort();
}

/** Deterministic pseudo-random chunk without allocating per call beyond the chunk itself. */
export function patternChunk(index, size = 1 << 20) {
  const u32 = new Uint32Array(size >> 2);
  let x = (0x9e3779b9 ^ Math.imul(index + 1, 0x85ebca6b)) >>> 0;
  for (let i = 0; i < u32.length; i++) {
    x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0;
    u32[i] = x;
  }
  return new Uint8Array(u32.buffer);
}

/** Samples JS heap size (Chromium only) and event-loop lag until stop() is called. */
export function startSampler({ intervalMs = 50 } = {}) {
  const hasHeap = !!performance.memory;
  const base = hasHeap ? performance.memory.usedJSHeapSize : null;
  let peak = base ?? 0, maxLag = 0, last = performance.now();
  const id = setInterval(() => {
    const now = performance.now();
    maxLag = Math.max(maxLag, now - last - intervalMs);
    last = now;
    if (hasHeap) peak = Math.max(peak, performance.memory.usedJSHeapSize);
  }, intervalMs);
  return {
    stop() {
      clearInterval(id);
      return { heapBaseBytes: base, heapPeakBytes: hasHeap ? peak : null, heapPeakGrowthBytes: hasHeap ? peak - base : null, maxMainThreadLagMs: Math.max(0, Math.round(maxLag)) };
    },
  };
}
