// Browser-side persistence, namespaced by server origin (IndexedDB is per-origin) and user id (database name).
// Holds the approved folder handle per space and the per-path baselines: the last state both sides acknowledged.
// Cleared browser data loses all of it, which the coordinator treats as a fresh, non-destructive enrollment.
import type { RootHandleLike } from "../fs/types";

export interface BindingRecord {
  spaceId: string;
  bindingId: string;
  deviceId: string;
  handle: RootHandleLike;
  rootName: string;
  enrollmentGeneration: number;
  /** Reconciliation epoch the baselines belong to. */
  epoch: string;
  connectedAt: number;
}

export interface BaselineRecord {
  path: string;
  type: "FILE" | "DIRECTORY";
  sha256?: string;
  size?: number;
  /** File mtime when the hash was verified; with size it allows skipping a rehash on an unchanged file. */
  mtime?: number;
  /** The path was ignored or oversized at the last plan; absence while suspended is never deletion intent. */
  suspended?: boolean;
}

/** Write-ahead record for one local file mutation. It exists from just before the first byte is written until the new baseline is stored. */
export interface JournalEntry {
  spaceId: string;
  path: string;
  planId: string;
  startedAt: number;
  /** Local content the plan assumed when it started (absent, or a file with this digest). */
  expected: { type: "ABSENT" | "FILE"; sha256?: string; size?: number };
  /** Content the operation is writing. */
  intended: { sha256: string; size: number };
}

/** An upload begun for one path, kept so a later explicit resume continues the same upload instead of starting over. */
export interface UploadRecord {
  spaceId: string;
  path: string;
  sha256: string;
  size: number;
  /** Idempotency key of the begin request: replaying it returns the same upload. */
  key: string;
  createdAt: number;
}

export interface SyncStore {
  getBinding(spaceId: string): Promise<BindingRecord | undefined>;
  allBindings(): Promise<BindingRecord[]>;
  putBinding(b: BindingRecord): Promise<void>;
  deleteBinding(spaceId: string): Promise<void>;
  getDeviceId(): Promise<string | undefined>;
  setDeviceId(id: string): Promise<void>;
  getMeta(key: string): Promise<string | undefined>;
  setMeta(key: string, value: string): Promise<void>;
  baselines(spaceId: string): Promise<Map<string, BaselineRecord>>;
  putBaselines(spaceId: string, records: BaselineRecord[]): Promise<void>;
  deleteBaselines(spaceId: string, paths: string[]): Promise<void>;
  clearBaselines(spaceId: string): Promise<void>;
  journal(spaceId: string): Promise<JournalEntry[]>;
  putJournal(entry: JournalEntry): Promise<void>;
  deleteJournal(spaceId: string, paths: string[]): Promise<void>;
  uploadRecord(spaceId: string, path: string): Promise<UploadRecord | undefined>;
  uploadRecords(spaceId: string): Promise<UploadRecord[]>;
  putUploadRecord(r: UploadRecord): Promise<void>;
  deleteUploadRecords(spaceId: string, paths: string[]): Promise<void>;
  /** Everything a run left unfinished for this space; non-zero means the next run is a resume. */
  clearRecovery(spaceId: string): Promise<void>;
}

const DB_VERSION = 2;

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error("transaction aborted"));
  });
}

export class IdbStore implements SyncStore {
  private constructor(private readonly db: IDBDatabase) {}

  static async open(userId: string): Promise<IdbStore> {
    const open = indexedDB.open(`foldersync:user:${userId}`, DB_VERSION);
    open.onupgradeneeded = (ev) => {
      const db = open.result;
      if (ev.oldVersion < 1) {
        db.createObjectStore("bindings", { keyPath: "spaceId" });
        db.createObjectStore("meta", { keyPath: "key" });
        const b = db.createObjectStore("baselines", { keyPath: ["spaceId", "path"] });
        b.createIndex("bySpace", "spaceId");
      }
      if (ev.oldVersion < 2) {
        db.createObjectStore("journal", { keyPath: ["spaceId", "path"] }).createIndex("bySpace", "spaceId");
        db.createObjectStore("uploads", { keyPath: ["spaceId", "path"] }).createIndex("bySpace", "spaceId");
      }
    };
    return new IdbStore(await req(open));
  }

  async getBinding(spaceId: string) { return (await req(this.db.transaction("bindings").objectStore("bindings").get(spaceId))) as BindingRecord | undefined; }
  async allBindings() { return (await req(this.db.transaction("bindings").objectStore("bindings").getAll())) as BindingRecord[]; }
  async putBinding(b: BindingRecord) {
    const tx = this.db.transaction("bindings", "readwrite");
    tx.objectStore("bindings").put(b);
    await done(tx);
  }
  async deleteBinding(spaceId: string) {
    const tx = this.db.transaction("bindings", "readwrite");
    tx.objectStore("bindings").delete(spaceId);
    await done(tx);
  }

  async getDeviceId() {
    const r = (await req(this.db.transaction("meta").objectStore("meta").get("deviceId"))) as { value: string } | undefined;
    return r?.value;
  }
  async setDeviceId(id: string) {
    const tx = this.db.transaction("meta", "readwrite");
    tx.objectStore("meta").put({ key: "deviceId", value: id });
    await done(tx);
  }

  async getMeta(key: string) {
    const r = (await req(this.db.transaction("meta").objectStore("meta").get(key))) as { value: string } | undefined;
    return r?.value;
  }
  async setMeta(key: string, value: string) {
    const tx = this.db.transaction("meta", "readwrite");
    tx.objectStore("meta").put({ key, value });
    await done(tx);
  }

  async baselines(spaceId: string) {
    const rows = (await req(this.db.transaction("baselines").objectStore("baselines").index("bySpace").getAll(spaceId))) as Array<BaselineRecord & { spaceId: string }>;
    return new Map(rows.map((r) => [r.path, r as BaselineRecord]));
  }
  async putBaselines(spaceId: string, records: BaselineRecord[]) {
    if (records.length === 0) return;
    const tx = this.db.transaction("baselines", "readwrite");
    const os = tx.objectStore("baselines");
    for (const r of records) os.put({ ...r, spaceId });
    await done(tx);
  }
  async deleteBaselines(spaceId: string, paths: string[]) {
    if (paths.length === 0) return;
    const tx = this.db.transaction("baselines", "readwrite");
    for (const p of paths) tx.objectStore("baselines").delete([spaceId, p]);
    await done(tx);
  }
  async clearBaselines(spaceId: string) {
    const tx = this.db.transaction("baselines", "readwrite");
    const os = tx.objectStore("baselines");
    const keys = await req(os.index("bySpace").getAllKeys(spaceId));
    for (const k of keys) os.delete(k as IDBValidKey);
    await done(tx);
  }

  async journal(spaceId: string) { return (await req(this.db.transaction("journal").objectStore("journal").index("bySpace").getAll(spaceId))) as JournalEntry[]; }
  async putJournal(entry: JournalEntry) {
    const tx = this.db.transaction("journal", "readwrite");
    tx.objectStore("journal").put(entry);
    await done(tx);
  }
  async deleteJournal(spaceId: string, paths: string[]) {
    if (paths.length === 0) return;
    const tx = this.db.transaction("journal", "readwrite");
    for (const p of paths) tx.objectStore("journal").delete([spaceId, p]);
    await done(tx);
  }
  async uploadRecord(spaceId: string, path: string) { return (await req(this.db.transaction("uploads").objectStore("uploads").get([spaceId, path]))) as UploadRecord | undefined; }
  async uploadRecords(spaceId: string) { return (await req(this.db.transaction("uploads").objectStore("uploads").index("bySpace").getAll(spaceId))) as UploadRecord[]; }
  async putUploadRecord(r: UploadRecord) {
    const tx = this.db.transaction("uploads", "readwrite");
    tx.objectStore("uploads").put(r);
    await done(tx);
  }
  async deleteUploadRecords(spaceId: string, paths: string[]) {
    if (paths.length === 0) return;
    const tx = this.db.transaction("uploads", "readwrite");
    for (const p of paths) tx.objectStore("uploads").delete([spaceId, p]);
    await done(tx);
  }
  async clearRecovery(spaceId: string) {
    const tx = this.db.transaction(["journal", "uploads"], "readwrite");
    for (const name of ["journal", "uploads"]) {
      const os = tx.objectStore(name);
      const keys = await req(os.index("bySpace").getAllKeys(spaceId));
      for (const k of keys) os.delete(k as IDBValidKey);
    }
    await done(tx);
  }
}

/** Volatile store for tests. */
export class MemoryStore implements SyncStore {
  private readonly bindings = new Map<string, BindingRecord>();
  private readonly base = new Map<string, Map<string, BaselineRecord>>();
  private device: string | undefined;
  async getBinding(spaceId: string) { return this.bindings.get(spaceId); }
  async allBindings() { return [...this.bindings.values()]; }
  async putBinding(b: BindingRecord) { this.bindings.set(b.spaceId, b); }
  async deleteBinding(spaceId: string) { this.bindings.delete(spaceId); }
  async getDeviceId() { return this.device; }
  async setDeviceId(id: string) { this.device = id; }
  private readonly meta = new Map<string, string>();
  async getMeta(key: string) { return this.meta.get(key); }
  async setMeta(key: string, value: string) { this.meta.set(key, value); }
  async baselines(spaceId: string) { return new Map(this.base.get(spaceId) ?? []); }
  async putBaselines(spaceId: string, records: BaselineRecord[]) {
    const m = this.base.get(spaceId) ?? new Map<string, BaselineRecord>();
    for (const r of records) m.set(r.path, r);
    this.base.set(spaceId, m);
  }
  async deleteBaselines(spaceId: string, paths: string[]) { for (const p of paths) this.base.get(spaceId)?.delete(p); }
  async clearBaselines(spaceId: string) { this.base.delete(spaceId); }

  private readonly journalRows = new Map<string, JournalEntry>();
  private readonly uploadRows = new Map<string, UploadRecord>();
  async journal(spaceId: string) { return [...this.journalRows.values()].filter((e) => e.spaceId === spaceId); }
  async putJournal(entry: JournalEntry) { this.journalRows.set(`${entry.spaceId}|${entry.path}`, entry); }
  async deleteJournal(spaceId: string, paths: string[]) { for (const p of paths) this.journalRows.delete(`${spaceId}|${p}`); }
  async uploadRecord(spaceId: string, path: string) { return this.uploadRows.get(`${spaceId}|${path}`); }
  async uploadRecords(spaceId: string) { return [...this.uploadRows.values()].filter((e) => e.spaceId === spaceId); }
  async putUploadRecord(r: UploadRecord) { this.uploadRows.set(`${r.spaceId}|${r.path}`, r); }
  async deleteUploadRecords(spaceId: string, paths: string[]) { for (const p of paths) this.uploadRows.delete(`${spaceId}|${p}`); }
  async clearRecovery(spaceId: string) {
    for (const k of [...this.journalRows.keys()]) if (k.startsWith(`${spaceId}|`)) this.journalRows.delete(k);
    for (const k of [...this.uploadRows.keys()]) if (k.startsWith(`${spaceId}|`)) this.uploadRows.delete(k);
  }
}
