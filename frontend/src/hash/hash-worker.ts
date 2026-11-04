// Dedicated worker: incremental SHA-256 over bounded reads. Messages in: {type:"hash", id, file} | {type:"cancel", id}.
// Messages out: {type:"done", id, hash} | {type:"cancelled", id} | {type:"error", id, message}.
import { hashBlob } from "./sha256";

const cancelled = new Set<number>();
const active = new Set<number>();

interface HashMsg { type: "hash"; id: number; file: Blob }
interface CancelMsg { type: "cancel"; id: number }

self.onmessage = async (ev: MessageEvent<HashMsg | CancelMsg>) => {
  const m = ev.data;
  if (m.type === "cancel") { if (active.has(m.id)) cancelled.add(m.id); return; }
  if (m.type !== "hash") return;
  active.add(m.id);
  try {
    const hash = await hashBlob(m.file, () => cancelled.has(m.id));
    (self as unknown as Worker).postMessage({ type: "done", id: m.id, hash });
  } catch (e) {
    const err = e as Error & { cancelled?: boolean };
    (self as unknown as Worker).postMessage(err.cancelled ? { type: "cancelled", id: m.id } : { type: "error", id: m.id, message: `${err.name}: ${err.message}` });
  } finally {
    active.delete(m.id);
    cancelled.delete(m.id);
  }
};
