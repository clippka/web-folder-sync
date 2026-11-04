/** Fixed-size pool of hash workers with a FIFO queue and cancellation. */
export class HashPool {
  constructor(size = 4, workerUrl = new URL('../hash-worker.js', import.meta.url)) {
    this.workers = Array.from({ length: size }, () => {
      const w = new Worker(workerUrl, { type: 'module' });
      w.busy = null;
      w.onmessage = (ev) => this._onMessage(w, ev.data);
      w.onerror = (ev) => { const job = w.busy; w.busy = null; job?.reject(new Error(`worker error: ${ev.message}`)); this._pump(); };
      return w;
    });
    this.queue = [];
    this.nextId = 1;
    this.generation = 0;
  }

  /** Resolves {hash, bytes, ms}; rejects with {cancelled:true} when cancelled. */
  hash(file, { chunkSize, mode, onProgress } = {}) {
    return new Promise((resolve, reject) => {
      this.queue.push({ id: this.nextId++, file, chunkSize, mode, onProgress, resolve, reject, gen: this.generation });
      this._pump();
    });
  }

  _pump() {
    for (const w of this.workers) {
      if (w.busy || !this.queue.length) continue;
      const job = this.queue.shift();
      w.busy = job;
      w.postMessage({ type: 'hash', id: job.id, file: job.file, chunkSize: job.chunkSize, mode: job.mode });
    }
  }

  _onMessage(w, m) {
    const job = w.busy;
    if (!job || job.id !== m.id) return;
    if (m.type === 'progress') { job.onProgress?.(m.bytes); return; }
    w.busy = null;
    if (m.type === 'done') job.resolve({ hash: m.hash, bytes: m.bytes, ms: m.ms });
    else if (m.type === 'cancelled') job.reject(Object.assign(new Error('cancelled'), { cancelled: true }));
    else job.reject(new Error(m.message));
    this._pump();
  }

  /** Drops queued jobs and asks running workers to stop. Resolves once every worker is idle. */
  async cancelAll() {
    const t0 = performance.now();
    this.generation++;
    for (const j of this.queue.splice(0)) j.reject(Object.assign(new Error('cancelled'), { cancelled: true }));
    for (const w of this.workers) if (w.busy) w.postMessage({ type: 'cancel', id: w.busy.id });
    while (this.workers.some((w) => w.busy)) await new Promise((r) => setTimeout(r, 2));
    return performance.now() - t0;
  }

  terminate() { for (const w of this.workers) w.terminate(); }
}
