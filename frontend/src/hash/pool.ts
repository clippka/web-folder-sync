// Bounded pool of hash workers. Hashing never runs on the page's main thread.
export interface Hasher {
  hash(file: Blob, signal?: AbortSignal): Promise<string>;
  terminate(): void;
}

interface Job { id: number; file: Blob; resolve(h: string): void; reject(e: Error): void }
interface Slot { worker: Worker; job: Job | null }

export function cancelledError(): Error {
  return Object.assign(new Error("cancelled"), { cancelled: true });
}

export function isCancelled(e: unknown): boolean {
  return !!e && typeof e === "object" && (e as { cancelled?: boolean }).cancelled === true;
}

export class HashPool implements Hasher {
  private readonly slots: Slot[];
  private readonly queue: Job[] = [];
  private nextId = 1;

  constructor(size: number, workerUrl: string | URL) {
    this.slots = Array.from({ length: size }, () => {
      const worker = new Worker(workerUrl, { type: "module" });
      const slot: Slot = { worker, job: null };
      worker.onmessage = (ev: MessageEvent) => this.onMessage(slot, ev.data as { type: string; id: number; hash?: string; message?: string });
      worker.onerror = (ev) => { const j = slot.job; slot.job = null; j?.reject(new Error(`hash worker error: ${ev.message}`)); this.pump(); };
      return slot;
    });
  }

  hash(file: Blob, signal?: AbortSignal): Promise<string> {
    return new Promise((resolve, reject) => {
      if (signal?.aborted) return reject(cancelledError());
      const job: Job = { id: this.nextId++, file, resolve, reject };
      signal?.addEventListener("abort", () => this.cancel(job), { once: true });
      this.queue.push(job);
      this.pump();
    });
  }

  private cancel(job: Job): void {
    const q = this.queue.indexOf(job);
    if (q >= 0) { this.queue.splice(q, 1); job.reject(cancelledError()); return; }
    this.slots.find((s) => s.job === job)?.worker.postMessage({ type: "cancel", id: job.id });
  }

  private pump(): void {
    for (const slot of this.slots) {
      if (slot.job || !this.queue.length) continue;
      const job = this.queue.shift()!;
      slot.job = job;
      slot.worker.postMessage({ type: "hash", id: job.id, file: job.file });
    }
  }

  private onMessage(slot: Slot, m: { type: string; id: number; hash?: string; message?: string }): void {
    const job = slot.job;
    if (!job || job.id !== m.id) return;
    slot.job = null;
    if (m.type === "done") job.resolve(m.hash!);
    else if (m.type === "cancelled") job.reject(cancelledError());
    else job.reject(new Error(m.message ?? "hash failed"));
    this.pump();
  }

  terminate(): void {
    for (const j of this.queue.splice(0)) j.reject(cancelledError());
    for (const s of this.slots) s.worker.terminate();
  }
}
