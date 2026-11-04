// One user-started run: scan -> plan -> (preview stops here) -> stage uploads -> commit -> apply downloads -> record.
//
// Safety rules enforced here:
//  * Nothing in this module starts by itself; callers pass a mode and an AbortSignal for an explicit click.
//  * A baseline is written only after the bytes on both sides are known to match (server digest check for uploads,
//    post-write re-hash for downloads). A failed path keeps its old baseline.
//  * The local file is rechecked immediately before it is replaced.
//  * A stale plan, policy or epoch causes a replan, never an overwrite.
//  * Deletions run only for paths the person confirmed, only if a fresh plan still proposes them, and only after the
//    server (or the local bytes) still match what was reviewed. Files are removed before their folders, non-recursively.
import { Api, ApiError, newKey } from "../api/client";
import type { Hasher } from "../hash/pool";
import { isCancelled } from "../hash/pool";
import type { BaselineRecord, BindingRecord, SyncStore } from "../idb";
import { isNotFound, resolveDir, splitPath, type DirHandleLike, type FileHandleLike } from "../fs/types";
import { buildPlanInput, scanFolder, type ScanResult, type StatCache } from "../scan";
import { downloadFile, ensureDirectory, isStaleUpload, uploadFile } from "../transfer";
import { describeRecovery, recoverInterrupted } from "../journal";
import { reviewDeletions, type DeletionReview } from "./deletion";

// ---- wire types -------------------------------------------------------------------------------------------------

export interface Limits {
  maxFileSizeBytes: number; maxIncludedFiles: number; chunkSizeBytes: number; maxManifestBatch: number;
  deletionEnabled?: boolean; deletionWarnCount?: number; deletionWarnPercent?: number;
}
export interface SpaceInfo { id: string; name: string; revision: number; policyRevision: number; epoch: string; limits: Limits }
export interface WireState { type: "ABSENT" | "FILE" | "DIRECTORY"; sha256?: string | null; size?: number | null }
export interface WireOp {
  seq: number; kind: string; path: string; needsDecision: boolean; state: WireState | null; expected: WireState | null;
  reason: string | null; cause: string | null; blobId: string | null;
}
export interface WirePlan {
  id: string; state: string; phase: string | null; serverRevision: number | null; policyRevision: number; epoch: string;
  paused: boolean; rescanRequired: boolean; scanComplete: boolean; counts: Record<string, number>; decisionCount: number;
  rejected: Array<{ path: string; reason: string }>; rejectedCount: number; leaseId: string | null;
}
interface CommitResult {
  applied: Array<{ path: string; kind: string; version: number }>;
  conflicts: Array<{ id: string; path: string; existing: boolean }>;
  skipped: Array<{ path: string; reason: string }>;
  pendingDecisions: string[];
  policyChanged: boolean;
  rescanRequired: boolean;
}

// ---- reporting ---------------------------------------------------------------------------------------------------

export type FileStatus = "queued" | "hashing" | "uploading" | "downloading" | "done" | "adopted" | "needs-review" | "blocked" | "skipped" | "failed";

export interface RunUi {
  phase(text: string): void;
  /** 0..1, or undefined for indeterminate. */
  progress(fraction?: number): void;
  file(path: string, status: FileStatus, detail?: string): void;
}

export type RunStatus = "up-to-date" | "completed" | "partial" | "needs-review" | "paused" | "failed" | "cancelled" | "preview" | "permission-required";

export interface RunOutcome {
  mode: "scan" | "sync";
  status: RunStatus;
  message: string;
  finishedAt: number;
  scannedFiles: number;
  scannedDirectories: number;
  hashed: number;
  reusedHashes: number;
  ignoredCount: number;
  ignored: ScanResult["ignored"];
  /** Operation counts by kind, from the last plan. */
  planned: Record<string, number>;
  done: { uploaded: number; downloaded: number; adopted: number; directories: number; deleted: number };
  failures: Array<{ path: string; message: string }>;
  review: Array<{ path: string; kind: string; detail?: string }>;
  /** Proposed deletions, grouped for review. A proposal is only acted on in a run started with the person's confirmation. */
  deletions: DeletionReview | null;
  unsupported: Array<{ path: string; reason: string }>;
  oversized: string[];
  notices: string[];
}

export interface RunContext {
  api: Api;
  store: SyncStore;
  hasher: Hasher;
  root: DirHandleLike;
  binding: BindingRecord;
  signal: AbortSignal;
  ui: RunUi;
  now?: () => number;
  /**
   * Paths of deletion proposals the person confirmed after reading them. The run plans afresh and acts on a path only if
   * the new plan still proposes its deletion; an approval can never authorize an operation it was not shown for.
   */
  approvedDeletions?: ReadonlySet<string>;
}

const MAX_PASSES = 4;
const UPLOAD_CONCURRENCY = 4;
const LEASE_RENEW_MS = 5 * 60_000;

class ReplanSignal extends Error { constructor(readonly why: string) { super(why); } }

export function blankOutcome(mode: "scan" | "sync"): RunOutcome {
  return {
    mode, status: "completed", message: "", finishedAt: 0, scannedFiles: 0, scannedDirectories: 0, hashed: 0, reusedHashes: 0,
    ignoredCount: 0, ignored: [], planned: {}, done: { uploaded: 0, downloaded: 0, adopted: 0, directories: 0, deleted: 0 },
    failures: [], review: [], deletions: null, unsupported: [], oversized: [], notices: [],
  };
}

export async function mapPool<T>(items: readonly T[], size: number, fn: (item: T) => Promise<void>): Promise<void> {
  let next = 0;
  const workers = Array.from({ length: Math.min(size, items.length) }, async () => {
    while (next < items.length) { const i = next++; await fn(items[i]!); }
  });
  await Promise.all(workers);
}

/** Local state of one path, distinguishing "not there" from "something of the wrong type is in the way". */
export type LocalLookup =
  | { kind: "absent" }
  | { kind: "file"; handle: FileHandleLike; file: File }
  | { kind: "directory" }
  | { kind: "blocked"; why: string };

export async function lookupLocal(root: DirHandleLike, path: string): Promise<LocalLookup> {
  const { parent, name } = splitPath(path);
  let dir: DirHandleLike;
  try { dir = await resolveDir(root, parent, false); }
  catch (e) {
    const n = (e as { name?: string }).name;
    if (n === "NotFoundError") return { kind: "absent" };
    if (n === "TypeMismatchError") return { kind: "blocked", why: "A file is in the way of a folder on this path" };
    throw e;
  }
  try { const handle = await dir.getFileHandle(name); return { kind: "file", handle, file: await handle.getFile() }; }
  catch (e) { if (!isNotFound(e)) throw e; }
  try { await dir.getDirectoryHandle(name); return { kind: "directory" }; }
  catch (e) { if (!isNotFound(e)) throw e; }
  return { kind: "absent" };
}

function toBaseline(path: string, s: WireState, mtime?: number): BaselineRecord {
  return s.type === "DIRECTORY"
    ? { path, type: "DIRECTORY" }
    : { path, type: "FILE", sha256: s.sha256 ?? "", size: s.size ?? 0, ...(mtime !== undefined ? { mtime } : {}) };
}

function describe(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  return (e as Error)?.message ?? String(e);
}

// ---- the run -------------------------------------------------------------------------------------------------------

export async function runSpace(ctx: RunContext, mode: "scan" | "sync"): Promise<RunOutcome> {
  const out = blankOutcome(mode);
  const now = ctx.now ?? Date.now;
  try {
    for (let pass = 1; pass <= MAX_PASSES; pass++) {
      const again = await onePass(ctx, mode, out, pass);
      if (!again) break;
      if (pass === MAX_PASSES) { out.status = "failed"; out.message = "The space kept changing during this run. Run it again."; }
    }
  } catch (e) {
    if (isCancelled(e) || ctx.signal.aborted) { out.status = "cancelled"; out.message = "Cancelled. Nothing further was changed; completed files stay in sync. Press Resume sync to continue where it stopped."; }
    else if (e instanceof ApiError && e.status === 401) { out.status = "failed"; out.message = "Your session ended. Sign in again, then run it again."; }
    else if (e instanceof ApiError && (e.code === "binding_inactive" || e.code === "device_revoked" || e.code === "not_found")) {
      out.status = "failed"; out.message = "This folder's connection no longer exists on the server. Disconnect it here and connect the folder again.";
    } else if (e instanceof ApiError && e.status === 0) { out.status = "failed"; out.message = "The server could not be reached. Check your connection and run it again."; }
    else { out.status = "failed"; out.message = describe(e); }
  }
  out.finishedAt = now();
  if (out.status === "completed" && out.failures.length) out.status = "partial";
  if (out.status === "completed" && out.review.length) out.status = "needs-review";
  const d = out.done;
  if (mode === "sync" && out.status === "completed" && !(d.uploaded || d.downloaded || d.directories || d.adopted || d.deleted)) {
    out.status = "up-to-date";
    out.message = "Everything is up to date.";
  }
  return out;
}

async function onePass(ctx: RunContext, mode: "scan" | "sync", out: RunOutcome, pass: number): Promise<boolean> {
  const { api, store, ui, signal } = ctx;
  const spaceId = ctx.binding.spaceId;

  ui.phase(pass === 1 ? "Checking the space" : "Checking the space again");
  const space = await api.get<SpaceInfo>(`/api/spaces/${spaceId}`, undefined, signal);

  let binding = ctx.binding;
  if (pass === 1) {
    // Settle whatever an interrupted run left behind by looking at the bytes on disk, before any baseline is read.
    const report = await recoverInterrupted({ store, hasher: ctx.hasher, root: ctx.root, spaceId, signal, lookup: lookupLocal });
    out.notices.push(...describeRecovery(report));
  }
  let baselines = await store.baselines(spaceId);
  if (space.epoch !== binding.epoch) {
    await store.clearRecovery(spaceId);
    // The server's history changed (restore, rotation). Our baselines describe a lineage that no longer exists, so
    // they cannot be trusted as "unchanged since last sync": drop them and reconcile as a fresh, non-destructive join.
    out.notices.push("The server was restored or reset. Local sync records were discarded; nothing will be deleted because of this.");
    if (mode === "sync") {
      await store.clearBaselines(spaceId);
      binding = { ...binding, epoch: space.epoch };
      await store.putBinding(binding);
      ctx.binding = binding;
    }
    baselines = new Map();
  }

  // ---- scan ----
  ui.phase("Scanning the folder");
  const cache = new Map<string, StatCache>();
  for (const b of baselines.values()) if (b.type === "FILE" && b.sha256 && b.mtime !== undefined && b.size !== undefined) cache.set(b.path, { size: b.size, mtime: b.mtime, sha256: b.sha256 });
  const scan = await scanFolder({
    root: ctx.root, hasher: ctx.hasher, maxFileSizeBytes: space.limits.maxFileSizeBytes, cache, signal,
    onProgress: (p) => ui.phase(`Scanning: ${p.files} files, ${p.directories} folders${p.current ? ` - ${p.current}` : ""}`),
  });
  out.scannedFiles = scan.fileCount; out.scannedDirectories = scan.directoryCount;
  out.hashed += scan.hashedCount; out.reusedHashes += scan.reusedHashCount;
  out.ignoredCount = scan.ignoredCount; out.ignored = scan.ignored;
  out.oversized = [...scan.entries.values()].filter((e) => e.oversized).map((e) => e.path);
  for (const p of scan.problems) out.failures.push({ path: p.path || "(folder root)", message: `Could not read: ${p.message}` });
  if (!scan.complete) out.notices.push("Some paths could not be read. Nothing will be deleted from this scan.");

  // ---- plan ----
  ui.phase("Comparing with the server");
  const input = buildPlanInput(scan, baselines);
  if (mode === "sync" && input.suspendedPaths.length) {
    const marks = input.suspendedPaths.flatMap((p) => { const b = baselines.get(p); return b ? [{ ...b, suspended: true }] : []; });
    await store.putBaselines(spaceId, marks);
  }
  let plan: WirePlan;
  try { plan = await createPlan(ctx, space, scan, input.entries); }
  catch (e) { if (e instanceof ApiError && e.replan) return true; throw e; }
  const ops = await fetchOperations(api, plan.id, signal);
  out.planned = {};
  for (const op of ops) out.planned[op.kind] = (out.planned[op.kind] ?? 0) + 1;
  out.unsupported = plan.rejected;
  out.deletions = reviewDeletions(ops, { activeFiles: scan.fileCount, limits: space.limits });

  for (const op of ops) {
    if (op.kind === "SUSPENDED") ctx.ui.file(op.path, "skipped", op.reason === "OVERSIZED" ? "larger than the file size limit" : "ignored");
    else if (op.kind === "BLOCKED") { ctx.ui.file(op.path, "blocked", op.reason ?? undefined); }
  }

  if (mode === "scan") {
    if (plan.leaseId) await api.delete(`/api/leases/${plan.leaseId}`).catch(() => undefined);
    out.status = "preview";
    for (const op of ops) previewLine(ctx.ui, op);
    out.review = ops.filter((o) => o.needsDecision).map((o) => ({ path: o.path, kind: o.kind, ...(o.reason ? { detail: o.reason } : {}) }));
    out.message = summarizePreview(out);
    return false;
  }

  if (plan.paused) {
    out.status = "paused";
    out.message = "A conflict in the ignore files (.gitignore/.syncignore) paused this space. Resolve it before other files can sync.";
    out.review = ops.filter((o) => o.needsDecision).map((o) => ({ path: o.path, kind: o.kind, ...(o.reason ? { detail: o.reason } : {}) }));
    return false;
  }

  // ---- execute ----
  try {
    return await execute(ctx, space, plan, ops, scan, out);
  } catch (e) {
    if (e instanceof ReplanSignal) { out.notices.push(`Replanned: ${e.why}`); return true; }
    if (e instanceof ApiError && (e.replan || e.recovery)) { out.notices.push("The server changed while this ran; planning again."); return true; }
    throw e;
  }
}

function previewLine(ui: RunUi, op: WireOp): void {
  const label: Record<string, string> = {
    UPLOAD: "would upload", DOWNLOAD: "would download", ADOPT: "already identical", CONFLICT: "conflict: needs your decision",
    PROPOSE_SERVER_DELETION: "deletion proposed (needs confirmation)", PROPOSE_LOCAL_DELETION: "deletion proposed (needs confirmation)",
    ACKNOWLEDGE_DELETION: "removed on both sides",
  };
  const text = label[op.kind];
  if (text) ui.file(op.path, op.needsDecision ? "needs-review" : "queued", text);
}

function summarizePreview(out: RunOutcome): string {
  const p = out.planned;
  const parts = [
    p["UPLOAD"] ? `${p["UPLOAD"]} to upload` : "", p["DOWNLOAD"] ? `${p["DOWNLOAD"]} to download` : "", p["ADOPT"] ? `${p["ADOPT"]} already identical` : "",
    p["CONFLICT"] ? `${p["CONFLICT"]} conflicts` : "",
  ].filter(Boolean);
  return parts.length ? `Preview: ${parts.join(", ")}. Nothing was changed.` : "Preview: everything is up to date. Nothing was changed.";
}

async function createPlan(ctx: RunContext, space: SpaceInfo, scan: ScanResult, entries: ReturnType<typeof buildPlanInput>["entries"]): Promise<WirePlan> {
  const { api, signal } = ctx;
  const batch = space.limits.maxManifestBatch;
  const first = entries.slice(0, batch);
  let plan = await api.post<WirePlan>(`/api/spaces/${space.id}/plans`, {
    bindingId: ctx.binding.bindingId, epoch: space.epoch, baseRevision: space.revision, policyRevision: space.policyRevision,
    scanComplete: scan.complete, entries: first, final: entries.length <= batch,
  }, { signal });
  for (let i = batch; i < entries.length; i += batch) {
    plan = await api.post<WirePlan>(`/api/plans/${plan.id}/entries`, { entries: entries.slice(i, i + batch), final: i + batch >= entries.length }, { signal });
  }
  return plan;
}

async function fetchOperations(api: Api, planId: string, signal: AbortSignal): Promise<WireOp[]> {
  const all: WireOp[] = [];
  let after = -1;
  for (;;) {
    const page = await api.get<{ operations: WireOp[]; nextAfter: number | null }>(`/api/plans/${planId}/operations`, { after, limit: 500 }, signal);
    all.push(...page.operations);
    if (page.nextAfter === null) return all;
    after = page.nextAfter;
  }
}

// ---- execution -----------------------------------------------------------------------------------------------------

async function execute(ctx: RunContext, space: SpaceInfo, plan: WirePlan, ops: WireOp[], scan: ScanResult, out: RunOutcome): Promise<boolean> {
  const { api, store, ui, signal } = ctx;
  const spaceId = space.id;
  const fail = (path: string, message: string) => { out.failures.push({ path, message }); ui.file(path, "failed", message); };
  const completed: BaselineRecord[] = [];
  const removed: string[] = [];
  const acks: Array<{ path: string; outcome: "APPLIED"; sha256?: string }> = [];

  // Deletions: only paths the person confirmed AND the fresh plan still proposes; the server gate must be open.
  const gate = space.limits.deletionEnabled === true;
  const confirmed = ctx.approvedDeletions ?? new Set<string>();
  const approvedServer = gate ? ops.filter((o) => o.kind === "PROPOSE_SERVER_DELETION" && confirmed.has(o.path)).map((o) => o.path) : [];
  const approvedLocal = gate ? ops.filter((o) => o.kind === "PROPOSE_LOCAL_DELETION" && confirmed.has(o.path)).map((o) => o.path) : [];

  const transferOps = ops.filter((o) => ["UPLOAD", "DOWNLOAD", "ADOPT", "CONFLICT", "ACKNOWLEDGE_DELETION"].includes(o.kind));
  let finished = 0;
  const tick = () => ui.progress(transferOps.length ? Math.min(1, ++finished / transferOps.length) : undefined);
  ui.progress(0);

  // 1. Stage uploads (files and conflict candidates). Directories need no bytes.
  const uploads: Record<string, string> = {};
  const candidates: Record<string, string> = {};
  const toStage = ops.filter((o) => (o.kind === "UPLOAD" || o.kind === "CONFLICT") && o.state?.type === "FILE");
  ui.phase(toStage.length ? `Uploading ${toStage.length} file${toStage.length === 1 ? "" : "s"}` : "Preparing changes");
  await mapPool(toStage, UPLOAD_CONCURRENCY, async (op) => {
    if (signal.aborted) return;
    try {
      const local = await lookupLocal(ctx.root, op.path);
      if (local.kind !== "file") throw new Error("The file is no longer there; run again");
      if (local.file.size !== op.state!.size) throw new Error("The file changed after it was scanned; run again");
      ui.file(op.path, "uploading");
      const id = await stageUpload(ctx, spaceId, op.path, local.file, op.state!.sha256!, (sent, total) => ui.file(op.path, "uploading", `${Math.round((sent / Math.max(1, total)) * 100)}%`));
      (op.kind === "UPLOAD" ? uploads : candidates)[op.path] = id;
    } catch (e) {
      if (isCancelled(e)) return;
      fail(op.path, describe(e));
      if (op.kind === "CONFLICT") tick();
    }
  });
  if (signal.aborted) throw Object.assign(new Error("cancelled"), { cancelled: true });

  // 2. Commit the server side atomically. The same key makes a retry after a lost response safe.
  ui.phase("Saving changes on the server");
  const key = newKey();
  let commit: CommitResult;
  for (let attempt = 1; ; attempt++) {
    try {
      commit = await api.post<CommitResult>(`/api/plans/${plan.id}/commit`, {
        epoch: space.epoch, uploads, conflictCandidates: candidates, approvedServerDeletions: approvedServer, approvedLocalDeletions: approvedLocal,
      }, { headers: { "Idempotency-Key": key }, signal });
      break;
    } catch (e) {
      if (e instanceof ApiError && e.status === 0 && attempt < 3 && !signal.aborted) { await new Promise((r) => setTimeout(r, 500 * attempt)); continue; }
      throw e;
    }
  }
  // The commit answered, so the staged uploads are consumed (or the commit skipped them); either way they cannot be resumed.
  await store.deleteUploadRecords(spaceId, [...Object.keys(uploads), ...Object.keys(candidates)]);
  const applied = new Set(commit.applied.map((a) => a.path));
  for (const s of commit.skipped) fail(s.path, `Not applied: ${s.reason}`);

  // 3. Record uploads that the server now holds, after confirming the local file is still what was sent.
  for (const op of ops.filter((o) => o.kind === "UPLOAD")) {
    if (!applied.has(op.path)) continue;
    if (op.state?.type === "DIRECTORY") { completed.push({ path: op.path, type: "DIRECTORY" }); acks.push({ path: op.path, outcome: "APPLIED" }); out.done.directories++; ui.file(op.path, "done", "folder created on server"); tick(); continue; }
    const scanned = scan.entries.get(op.path);
    const local = await lookupLocal(ctx.root, op.path);
    if (local.kind !== "file" || !scanned) { fail(op.path, "Uploaded, but the local file changed meanwhile; run again to reconcile it"); tick(); continue; }
    let mtime = local.file.lastModified;
    if (local.file.size !== op.state!.size || local.file.lastModified !== scanned.mtime) {
      const rehash = await ctx.hasher.hash(local.file, signal);
      if (rehash !== op.state!.sha256) { fail(op.path, "Uploaded, but the local file changed meanwhile; run again to reconcile it"); tick(); continue; }
      mtime = local.file.lastModified;
    }
    completed.push({ path: op.path, type: "FILE", sha256: op.state!.sha256!, size: op.state!.size!, mtime });
    acks.push({ path: op.path, outcome: "APPLIED", sha256: op.state!.sha256! });
    out.done.uploaded++; ui.file(op.path, "done", "uploaded"); tick();
  }
  for (const c of commit.conflicts) { out.review.push({ path: c.path, kind: "CONFLICT", detail: "Both versions are kept until you decide" }); ui.file(c.path, "needs-review", "conflict: both versions kept"); tick(); }
  for (const o of ops.filter((x) => x.kind === "CONFLICT" && !commit.conflicts.some((c) => c.path === x.path) && !out.failures.some((f) => f.path === x.path))) {
    out.review.push({ path: o.path, kind: "CONFLICT", ...(o.reason ? { detail: o.reason } : {}) }); ui.file(o.path, "needs-review", "conflict");
  }
  // Server deletions the commit applied: the file is gone from the server, so its baseline goes too.
  for (const op of ops.filter((o) => o.kind === "PROPOSE_SERVER_DELETION" && approvedServer.includes(o.path))) {
    if (applied.has(op.path)) {
      removed.push(op.path); acks.push({ path: op.path, outcome: "APPLIED" }); out.done.deleted++; ui.file(op.path, "done", "deleted on the server");
    } else fail(op.path, "Not deleted: the server did not apply the confirmed deletion");
  }
  for (const o of ops.filter((x) => (x.kind === "PROPOSE_SERVER_DELETION" && !approvedServer.includes(x.path)) || (x.kind === "PROPOSE_LOCAL_DELETION" && !approvedLocal.includes(x.path)))) {
    out.review.push({ path: o.path, kind: o.kind, detail: "Deletions need your confirmation" }); ui.file(o.path, "needs-review", "deletion needs confirmation");
  }
  // Local deletions run last among the destructive steps, children before parents, each rechecked against the bytes reviewed.
  const localDeletes = ops.filter((o) => o.kind === "PROPOSE_LOCAL_DELETION" && approvedLocal.includes(o.path))
    .sort((a, b) => b.path.split("/").length - a.path.split("/").length);
  for (const op of localDeletes) {
    if (signal.aborted) break;
    try {
      const res = await removeLocal(ctx, op);
      if (res === "removed" || res === "already-gone") {
        removed.push(op.path); acks.push({ path: op.path, outcome: "APPLIED" }); out.done.deleted++;
        ui.file(op.path, "done", res === "removed" ? "deleted from this folder" : "already gone");
      }
    } catch (e) {
      if (isCancelled(e)) break;
      // The baseline stays, so nothing is mistaken for a new local deletion; the next run proposes it again.
      fail(op.path, `Not deleted: ${describe(e)}`);
    }
  }

  // 4. Apply server content locally, parents before children (operation order).
  let leaseAt = Date.now();
  const downloads = ops.filter((o) => o.kind === "DOWNLOAD");
  if (downloads.length) ui.phase(`Downloading ${downloads.length} item${downloads.length === 1 ? "" : "s"}`);
  for (const op of downloads) {
    if (signal.aborted) break;
    try {
      if (plan.leaseId && Date.now() - leaseAt > LEASE_RENEW_MS) { await api.post(`/api/leases/${plan.leaseId}/renew`, undefined, { signal }); leaseAt = Date.now(); }
      await applyDownload(ctx, spaceId, op, out, completed, acks, plan.id);
      tick();
    } catch (e) {
      if (isCancelled(e)) break;
      if (e instanceof ApiError && (e.code === "lease_expired" || e.code === "content_unavailable")) throw new ReplanSignal("downloads expired");
      fail(op.path, describe(e)); tick();
    }
  }
  if (signal.aborted) { await persist(ctx, spaceId, completed, removed); throw Object.assign(new Error("cancelled"), { cancelled: true }); }

  // 5. Identical content on both sides: record the shared baseline without any transfer.
  for (const op of ops.filter((o) => o.kind === "ADOPT")) {
    const scanned = scan.entries.get(op.path);
    if (!scanned || !op.state) { fail(op.path, "Could not confirm the local content"); tick(); continue; }
    completed.push(op.state.type === "DIRECTORY" ? { path: op.path, type: "DIRECTORY" } : { path: op.path, type: "FILE", sha256: op.state.sha256!, size: op.state.size!, ...(scanned.mtime !== undefined ? { mtime: scanned.mtime } : {}) });
    acks.push({ path: op.path, outcome: "APPLIED", ...(op.state.sha256 ? { sha256: op.state.sha256 } : {}) });
    out.done.adopted++; ui.file(op.path, "adopted", "already identical"); tick();
  }
  for (const op of ops.filter((o) => o.kind === "ACKNOWLEDGE_DELETION")) { removed.push(op.path); acks.push({ path: op.path, outcome: "APPLIED" }); tick(); }
  for (const o of ops.filter((x) => x.kind === "SUSPENDED")) if (o.reason === "OVERSIZED" && !out.oversized.includes(o.path)) out.oversized.push(o.path);

  await persist(ctx, spaceId, completed, removed);

  // 6. Tell the server what completed. This is a record, not a substitute for the local verification above.
  for (let i = 0; i < acks.length; i += space.limits.maxManifestBatch) {
    try { await api.post(`/api/plans/${plan.id}/acknowledgements`, { entries: acks.slice(i, i + space.limits.maxManifestBatch) }, { signal }); }
    catch (e) { if (isCancelled(e)) break; out.notices.push(`Could not record some completions on the server (${describe(e)}). Local records are saved.`); break; }
  }

  out.message = describeOutcome(out, commit);
  if (plan.phase === "CONTROL" || commit.rescanRequired || commit.policyChanged) {
    out.notices.push("Ignore rules changed; scanning again with the new rules.");
    return true;
  }
  return false;
}

async function persist(ctx: RunContext, spaceId: string, completed: BaselineRecord[], removed: string[]): Promise<void> {
  await ctx.store.putBaselines(spaceId, completed);
  await ctx.store.deleteBaselines(spaceId, removed);
}

/**
 * Uploads one file, continuing an earlier upload of the same bytes when a previous run left one behind. The begin key is
 * stored before the first request so an interruption at any point can be picked up by the next explicit run.
 */
async function stageUpload(ctx: RunContext, spaceId: string, path: string, file: File, sha256: string, onBytes: (sent: number, total: number) => void): Promise<string> {
  const { api, store, signal } = ctx;
  const now = ctx.now ?? Date.now;
  const prior = await store.uploadRecord(spaceId, path);
  const reusable = prior !== undefined && prior.sha256 === sha256 && prior.size === file.size;
  if (reusable) {
    try { return await uploadFile(api, spaceId, file, sha256, signal, onBytes, { key: prior.key, resume: true }); }
    catch (e) { if (!isStaleUpload(e)) throw e; }
  }
  const record = { spaceId, path, sha256, size: file.size, key: newKey(), createdAt: now() };
  await store.putUploadRecord(record);
  try { return await uploadFile(api, spaceId, file, sha256, signal, onBytes, { key: record.key }); }
  catch (e) {
    // A definite rejection cannot be continued; an interruption (cancel, connection) keeps the record for the resume.
    if (e instanceof ApiError && e.status !== 0 && !e.retryable) await store.deleteUploadRecords(spaceId, [path]);
    throw e;
  }
}

/**
 * Removes one confirmed local deletion. A file is removed only if it still has exactly the reviewed content; a folder
 * only if it is empty (never recursively). Anything unexpected leaves the path alone and reports why.
 */
export async function removeLocal(ctx: RunContext, op: WireOp): Promise<"removed" | "already-gone"> {
  const local = await lookupLocal(ctx.root, op.path);
  const expected = op.expected ?? { type: "ABSENT" as const };
  if (local.kind === "blocked") throw new Error(local.why);
  if (local.kind === "absent") return "already-gone";
  const { parent, name } = splitPath(op.path);
  const dir = await resolveDir(ctx.root, parent, false);
  if (expected.type === "DIRECTORY") {
    if (local.kind !== "directory") throw new Error("A file is here where a folder was expected; left alone");
    try { await dir.removeEntry(name); }
    catch (e) {
      const n = (e as { name?: string }).name;
      if (n === "InvalidModificationError") throw new Error("The folder still contains files (for example ignored or new ones), so it was kept");
      throw e;
    }
    return "removed";
  }
  if (local.kind !== "file") throw new Error("A folder is here where a file was expected; left alone");
  const current = await ctx.hasher.hash(local.file, ctx.signal);
  if (current !== expected.sha256) throw new Error("The file changed since the scan, so it was kept");
  const again = await lookupLocal(ctx.root, op.path);
  if (again.kind !== "file" || again.file.size !== local.file.size || again.file.lastModified !== local.file.lastModified) {
    throw new Error("The file changed while checking it, so it was kept");
  }
  await dir.removeEntry(name);
  return "removed";
}

export async function applyDownload(ctx: RunContext, spaceId: string, op: WireOp, out: RunOutcome, completed: BaselineRecord[], acks: Array<{ path: string; outcome: "APPLIED"; sha256?: string }>, planId = "decision"): Promise<void> {
  const { signal, ui } = ctx;
  const state = op.state!;
  const local = await lookupLocal(ctx.root, op.path);
  const expected = op.expected ?? { type: "ABSENT" as const };

  // Recheck the local state the plan assumed, immediately before changing anything.
  if (local.kind === "blocked") throw new Error(local.why);
  if (state.type === "DIRECTORY") {
    if (local.kind === "file") throw new Error("A file with this name exists here; resolve it first");
    ui.file(op.path, "downloading", "creating folder");
    await ensureDirectory(ctx.root, op.path);
    completed.push({ path: op.path, type: "DIRECTORY" });
    acks.push({ path: op.path, outcome: "APPLIED" });
    out.done.directories++; ui.file(op.path, "done", "folder created");
    return;
  }
  if (local.kind === "directory") throw new Error("A folder with this name exists here; resolve it first");
  if (expected.type === "ABSENT") {
    if (local.kind !== "absent") throw new Error("A file appeared here since the scan; run again");
  } else if (expected.type === "FILE") {
    if (local.kind !== "file") throw new Error("The local file is gone; run again");
    const current = await ctx.hasher.hash(local.file, signal);
    if (current !== expected.sha256) throw new Error("The local file changed since the scan; run again so your edit is not overwritten");
  }

  // Write-ahead: if the browser dies from here on, the next explicit run finds this entry and settles it from the disk.
  await ctx.store.putJournal({
    spaceId, path: op.path, planId, startedAt: (ctx.now ?? Date.now)(),
    expected: expected.type === "FILE" ? { type: "FILE", sha256: expected.sha256!, size: expected.size! } : { type: "ABSENT" },
    intended: { sha256: state.sha256!, size: state.size! },
  });
  // The last look at the target before it is replaced: it must still be exactly what was checked above.
  const beforeReplace = async () => {
    const now = await lookupLocal(ctx.root, op.path);
    if (local.kind === "absent") {
      // The download itself created an empty placeholder to write into; anything with content is somebody else's file.
      if (now.kind === "absent" || (now.kind === "file" && now.file.size === 0)) return;
      throw new Error("A file appeared here while downloading; run again so it is not overwritten");
    }
    if (local.kind !== "file" || now.kind !== "file" || now.file.size !== local.file.size || now.file.lastModified !== local.file.lastModified) {
      throw new Error("The local file changed while downloading; run again so your edit is not overwritten");
    }
  };
  ui.file(op.path, "downloading");
  let res;
  try {
    res = await downloadFile(ctx.api, ctx.hasher, ctx.root, op.path, spaceId, op.blobId!, { sha256: state.sha256!, size: state.size! }, signal,
      (n, total) => ui.file(op.path, "downloading", `${Math.round((n / Math.max(1, total)) * 100)}%`), beforeReplace);
  } catch (e) {
    // Failed before the replacement (aborted write): the folder is untouched, so there is nothing to recover.
    if (!(e as { localChanged?: boolean }).localChanged) await ctx.store.deleteJournal(spaceId, [op.path]);
    throw e;
  }
  const baseline = toBaseline(op.path, state, res.mtime);
  // The baseline is stored per file, and only then is the journal entry released.
  await ctx.store.putBaselines(spaceId, [baseline]);
  await ctx.store.deleteJournal(spaceId, [op.path]);
  completed.push(baseline);
  acks.push({ path: op.path, outcome: "APPLIED", sha256: state.sha256! });
  out.done.downloaded++; ui.file(op.path, "done", "downloaded");
}

function describeOutcome(out: RunOutcome, commit: CommitResult): string {
  const d = out.done;
  const bits = [
    d.uploaded ? `${d.uploaded} uploaded` : "", d.downloaded ? `${d.downloaded} downloaded` : "", d.directories ? `${d.directories} folders` : "",
    d.adopted ? `${d.adopted} already identical` : "", d.deleted ? `${d.deleted} deleted` : "", commit.conflicts.length ? `${commit.conflicts.length} conflicts need review` : "",
    out.failures.length ? `${out.failures.length} failed` : "",
  ].filter(Boolean);
  return bits.length ? bits.join(", ") : "Everything is up to date.";
}
