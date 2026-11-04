// Dedicated module worker: incremental SHA-256 over bounded reads.
// Messages in:  {type:'hash', id, file, chunkSize, mode:'slice'|'stream'}  {type:'cancel', id}
// Messages out: {type:'done', id, hash, bytes, ms} | {type:'cancelled', id, bytes} | {type:'error', id, message} | {type:'progress', id, bytes}
import { Sha256 } from './lib/sha256.js';

const cancelled = new Set();
const active = new Set();

self.onmessage = async (ev) => {
  const m = ev.data;
  if (m.type === 'cancel') { if (active.has(m.id)) cancelled.add(m.id); return; }
  if (m.type !== 'hash') return;
  const { id, file, chunkSize = 1 << 20, mode = 'slice' } = m;
  active.add(id);
  const t0 = performance.now();
  try {
    const sha = new Sha256();
    let bytes = 0, sinceProgress = 0;
    const step = (u8) => {
      sha.update(u8);
      bytes += u8.length;
      sinceProgress += u8.length;
      if (sinceProgress >= 8 << 20) { sinceProgress = 0; self.postMessage({ type: 'progress', id, bytes }); }
    };
    if (mode === 'stream') {
      const reader = file.stream().getReader();
      for (;;) {
        if (cancelled.has(id)) { await reader.cancel(); throw Object.assign(new Error('cancelled'), { cancelled: true }); }
        const { done, value } = await reader.read();
        if (done) break;
        step(value);
      }
    } else {
      for (let off = 0; off < file.size; off += chunkSize) {
        if (cancelled.has(id)) throw Object.assign(new Error('cancelled'), { cancelled: true });
        step(new Uint8Array(await file.slice(off, Math.min(file.size, off + chunkSize)).arrayBuffer()));
      }
    }
    if (bytes !== file.size) throw new Error(`size changed while hashing: read ${bytes}, expected ${file.size}`);
    self.postMessage({ type: 'done', id, hash: sha.digestHex(), bytes, ms: performance.now() - t0 });
  } catch (e) {
    if (e.cancelled) self.postMessage({ type: 'cancelled', id });
    else self.postMessage({ type: 'error', id, message: `${e.name}: ${e.message}` });
  } finally {
    active.delete(id);
    cancelled.delete(id);
  }
};
