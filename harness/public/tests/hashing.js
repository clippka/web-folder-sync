import { Checks, freshScratch, patternChunk, startSampler, fmtBytes } from '../lib/util.js';
import { Sha256, sha256Hex } from '../lib/sha256.js';
import { HashPool } from '../lib/hash-pool.js';

const LIMIT = 104_857_600;
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

/** Writes `size` bytes of deterministic data through a writable stream; returns the expected hash. */
async function writeBig(dir, name, size) {
  const fh = await dir.getFileHandle(name, { create: true });
  const w = await fh.createWritable();
  const sha = new Sha256();
  const chunk = 1 << 20;
  for (let off = 0, i = 0; off < size; off += chunk, i++) {
    const u8 = patternChunk(i, chunk).subarray(0, Math.min(chunk, size - off));
    sha.update(u8);
    await w.write(u8);
  }
  await w.close();
  return sha.digestHex();
}

export default {
  id: 'hashing',
  title: 'Worker streaming SHA-256, bounded reads, 100 MiB boundary files',
  needsRoot: true,
  spec: '§7 step 5, §8, acceptance 13',
  options: [{ key: 'bigMiB', label: 'Boundary size (MiB)', default: 100 }],
  async run(ctx) {
    const c = new Checks();
    const data = {};

    // 1. Correctness against WebCrypto on small inputs (WebCrypto is one-shot, so only used for small buffers).
    let allMatch = true;
    for (const n of [0, 1, 55, 56, 63, 64, 65, 1000, 65536, 3_000_017]) {
      const u8 = patternChunk(n, 4 << 20).subarray(0, n);
      allMatch &&= sha256Hex(u8) === hex(await crypto.subtle.digest('SHA-256', u8));
    }
    c.check('JS SHA-256 equals WebCrypto for boundary lengths', allMatch);

    // 2. Boundary files, written and read via streams, hashed in a worker.
    const dir = await freshScratch(ctx.root);
    const pool = new HashPool(1);
    const exact = ctx.opts.bigMiB === 100 ? LIMIT : ctx.opts.bigMiB * (1 << 20);
    try {
      for (const [name, size] of [[`exact.bin`, exact], [`plus-one.bin`, exact + 1]]) {
        const t0 = performance.now();
        const sampler = startSampler();
        const expected = await writeBig(dir, name, size);
        const tWrite = performance.now() - t0;
        const file = await (await dir.getFileHandle(name)).getFile();
        c.check(`${name}: on-disk size is ${size}`, file.size === size, file.size);
        for (const mode of ['slice', 'stream']) {
          const t1 = performance.now();
          const r = await pool.hash(file, { mode, chunkSize: 1 << 20 });
          const dt = performance.now() - t1;
          c.check(`${name}: worker ${mode} hash equals streamed-write hash`, r.hash === expected);
          data[`${name}.${mode}`] = { ms: Math.round(dt), mibPerSec: Math.round(size / (1 << 20) / (dt / 1000)) };
        }
        const mem = sampler.stop();
        data[`${name}.memory`] = { ...mem, writeMs: Math.round(tWrite) };
        // 8 MiB of read chunks + one worker + overhead; the real budget is derived in the report.
        if (mem.heapPeakGrowthBytes != null) c.check(`${name}: main-thread JS heap growth < 64 MiB while writing + hashing (provisional bound)`, mem.heapPeakGrowthBytes < 64 << 20, fmtBytes(mem.heapPeakGrowthBytes));
        else c.note(`${name}: heap sampling unavailable`, 'performance.memory missing; use browser task manager');
        c.note(`${name}: max main-thread lag during write+hash`, `${mem.maxMainThreadLagMs} ms`);
      }
    } finally {
      pool.terminate();
    }

    c.note('boundary sizes used (policy decision on eligibility is Step 3/7)', { exact, plusOne: exact + 1 });
    return { checks: c.items, status: c.status, data };
  },
};
