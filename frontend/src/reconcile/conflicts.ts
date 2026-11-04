// Conflict review and decisions, executed from the browser that holds the local folder.
//
// The server records the decision against the version the person reviewed (stale decisions are refused), and this
// module applies it to the local folder. Order matters for safety:
//  1. check that the local folder still holds what the conflict recorded, before anything is decided;
//  2. record the decision on the server (idempotent, version-checked);
//  3. write local files through the same verified, staged download used by ordinary syncs;
//  4. write baselines only for bytes that were verified on disk.
// If step 3 fails the decision stands on the server, nothing local was lost, and the next Sync now reports the path
// again instead of guessing.
import { Api, ApiError, newKey } from "../api/client";
import type { Hasher } from "../hash/pool";
import type { BaselineRecord, BindingRecord, SyncStore } from "../idb";
import type { DirHandleLike } from "../fs/types";
import { applyDownload, blankOutcome, lookupLocal, type RunUi, type WireOp, type WireState } from "./run";

export type Resolution = "KEEP_LOCAL" | "KEEP_SERVER" | "KEEP_BOTH" | "SKIP";

export interface WireConflict {
  id: string; spaceId: string; path: string; kind: "CONTENT" | "DELETE_VS_EDIT" | "STRUCTURAL"; state: "UNRESOLVED" | "RESOLVED";
  resolution: string | null; deviceLabel: string | null; serverEntryVersion: number;
  local: WireState & { blobId?: string | null }; server: WireState & { blobId?: string | null };
  createdAt: number; resolvedAt: number | null; serverUpdatedAt: number | null; options: Resolution[]; bindingId: string | null;
}

export interface Preview { kind: "text" | "binary" | "empty" | "unavailable"; text: string | null; truncated: boolean; size: number | null; sha256: string | null }

export interface ResolveResponse { conflict: WireConflict; entryVersion: number | null; revision: number | null; siblingPath: string | null; siblingVersion: number | null }

export interface ResolveContext {
  api: Api; store: SyncStore; hasher: Hasher; root: DirHandleLike; binding: BindingRecord; signal: AbortSignal; ui: RunUi;
}

export interface ResolveReport {
  ok: boolean;
  message: string;
  /** The server accepted the decision (even if the local folder could not be updated yet). */
  recorded: boolean;
  refresh: boolean;
}

export async function loadConflicts(api: Api, spaceId: string, signal?: AbortSignal): Promise<WireConflict[]> {
  return api.get<WireConflict[]>(`/api/spaces/${spaceId}/conflicts`, { unresolved: "true" }, signal);
}

export function loadPreview(api: Api, conflictId: string, side: "local" | "server"): Promise<Preview> {
  return api.get<Preview>(`/api/conflicts/${conflictId}/preview`, { side });
}

export function blobUrl(spaceId: string, blobId: string): string { return `/api/spaces/${spaceId}/blobs/${blobId}/content`; }

/** What the person is deciding, in the words the review uses. */
export function optionLabel(c: WireConflict, r: Resolution): { label: string; help: string } {
  if (c.kind === "DELETE_VS_EDIT") {
    const deletedLocally = c.local.type === "ABSENT";
    const keepsFile = deletedLocally ? r === "KEEP_SERVER" : r === "KEEP_LOCAL";
    if (r === "KEEP_BOTH") return { label: "Keep both", help: "" };
    return keepsFile
      ? { label: "Keep modified file", help: "The edited file stays; nothing is deleted." }
      : { label: "Confirm deletion", help: "The file is deleted for good. FolderSync keeps no history, trash or restore." };
  }
  if (r === "KEEP_LOCAL") return { label: "Keep this folder's version", help: "The server's version is replaced by the one in this folder." };
  if (r === "KEEP_SERVER") return { label: "Keep server version", help: "This folder's version is replaced by the server's." };
  if (r === "KEEP_BOTH") return { label: "Keep both", help: "The server's version stays under the original name; this folder's version is saved beside it under a new name." };
  return { label: "Skip", help: "Decide later. Both versions are kept." };
}

/** True when the option removes a file and therefore needs the server's deletion gate to be open. */
export function isDeletion(c: WireConflict, r: Resolution): boolean {
  if (r === "KEEP_LOCAL") return c.local.type === "ABSENT";
  if (r === "KEEP_SERVER") return c.server.type === "ABSENT" && c.local.type !== "ABSENT";
  return false;
}

function describe(e: unknown): string { return e instanceof ApiError ? e.message : (e as Error)?.message ?? String(e); }

const synthetic = (kind: string, path: string, state: WireState, expected: WireState, blobId: string | null): WireOp =>
  ({ seq: 0, kind, path, needsDecision: false, state, expected, reason: null, cause: null, blobId });

export async function resolveConflict(ctx: ResolveContext, c: WireConflict, r: Resolution): Promise<ResolveReport> {
  if (r === "SKIP") return { ok: true, recorded: false, refresh: false, message: "Left undecided. Both versions are kept." };
  if (!c.options.includes(r)) return { ok: false, recorded: false, refresh: true, message: "This decision is not available for this conflict." };

  // 1. The local folder must still hold what the conflict recorded; otherwise a decision could overwrite newer work.
  const local = await lookupLocal(ctx.root, c.path);
  if (local.kind === "blocked") return { ok: false, recorded: false, refresh: false, message: local.why };
  let localSha: string | null = null;
  if (local.kind === "file") localSha = await ctx.hasher.hash(local.file, ctx.signal);
  const localType = local.kind === "file" ? "FILE" : local.kind === "directory" ? "DIRECTORY" : "ABSENT";
  const matchesCandidate = localType === c.local.type && (localType !== "FILE" || localSha === c.local.sha256);
  const matchesServer = localType === c.server.type && (localType !== "FILE" || localSha === c.server.sha256);
  const sameConnection = c.bindingId === ctx.binding.bindingId;

  if (r === "KEEP_LOCAL") {
    if (!matchesCandidate && !sameConnection) {
      return { ok: false, recorded: false, refresh: false, message: `This conflict was found by ${c.deviceLabel ?? "another folder"}. Decide it from there; this folder holds different content.` };
    }
  } else if (r === "KEEP_SERVER") {
    if (!(matchesCandidate || matchesServer || localType === "ABSENT")) {
      return { ok: false, recorded: false, refresh: false, message: "The file in this folder changed after the conflict was recorded, so it would be overwritten. Choose Keep this folder's version (your newer edit then uploads on the next sync) or copy your edits elsewhere first." };
    }
  } else if (r === "KEEP_BOTH" && !matchesCandidate) {
    return { ok: false, recorded: false, refresh: false, message: "This folder's file is not the one recorded in the conflict (it changed, or the conflict was found by another folder). Nothing was changed." };
  }

  // 2. Record the decision. A stale version or an unavailable option comes back as an error and changes nothing.
  let res: ResolveResponse;
  try {
    res = await ctx.api.post<ResolveResponse>(`/api/conflicts/${c.id}/resolve`, { resolution: r, expectedServerVersion: c.serverEntryVersion }, { headers: { "Idempotency-Key": newKey() }, signal: ctx.signal });
  } catch (e) {
    if (e instanceof ApiError && e.code === "stale_conflict") return { ok: false, recorded: false, refresh: true, message: "The file changed on the server after you opened this. The review was refreshed; decide again." };
    if (e instanceof ApiError && e.code === "already_resolved") return { ok: false, recorded: false, refresh: true, message: "This conflict was already decided elsewhere. Run Sync now to bring this folder up to date." };
    if (e instanceof ApiError && e.code === "deletion_disabled") return { ok: false, recorded: false, refresh: false, message: "Deleting files is not enabled on this server yet, so this decision is unavailable. Nothing was changed." };
    return { ok: false, recorded: false, refresh: e instanceof ApiError && e.status === 409, message: describe(e) };
  }

  // 3. Apply it locally with the verified download path (staged write, digest check, re-read after writing).
  const out = blankOutcome("sync");
  const dl = { ...ctx, ui: ctx.ui };
  const completed: BaselineRecord[] = [];
  const acks: Array<{ path: string; outcome: "APPLIED"; sha256?: string }> = [];
  const fileState = (s: WireState) => ({ type: "FILE" as const, sha256: s.sha256 ?? "", size: s.size ?? 0 });
  try {
    if (r === "KEEP_SERVER") {
      if (c.server.type === "FILE" && !matchesServer) {
        await applyDownload(dl, c.spaceId, synthetic("DOWNLOAD", c.path, fileState(c.server), localType === "FILE" ? fileState({ ...c.local, sha256: localSha }) : { type: "ABSENT" }, c.server.blobId ?? null), out, completed, acks);
      } else if (c.server.type === "FILE") {
        completed.push({ path: c.path, type: "FILE", sha256: c.server.sha256 ?? "", size: c.server.size ?? 0, ...(local.kind === "file" ? { mtime: local.file.lastModified } : {}) });
      } else if (c.server.type === "DIRECTORY") {
        completed.push({ path: c.path, type: "DIRECTORY" });
      } else {
        // Deleting the local file is Step 8. The server refuses the decision first while the gate is closed.
        return { ok: false, recorded: true, refresh: true, message: "The decision was recorded, but this browser cannot delete files yet. Run Sync now to review it." };
      }
    } else if (r === "KEEP_LOCAL") {
      if (c.local.type === "FILE") completed.push({ path: c.path, type: "FILE", sha256: c.local.sha256 ?? "", size: c.local.size ?? 0, ...(local.kind === "file" && matchesCandidate ? { mtime: local.file.lastModified } : {}) });
      else if (c.local.type === "DIRECTORY") completed.push({ path: c.path, type: "DIRECTORY" });
    } else if (r === "KEEP_BOTH") {
      const sibling = res.siblingPath;
      if (!sibling || !c.local.blobId || !c.server.blobId) return { ok: false, recorded: true, refresh: true, message: "The decision was recorded, but its details were incomplete. Run Sync now." };
      // The local bytes are already safe on the server as the sibling; save them beside the original first, then replace the original.
      await applyDownload(dl, c.spaceId, synthetic("DOWNLOAD", sibling, fileState(c.local), { type: "ABSENT" }, c.local.blobId), out, completed, acks);
      await applyDownload(dl, c.spaceId, synthetic("DOWNLOAD", c.path, fileState(c.server), fileState(c.local), c.server.blobId), out, completed, acks);
    }
    await ctx.store.putBaselines(c.spaceId, completed);
  } catch (e) {
    // Save whatever was verified before the failure; the rest is reconciled by the next Sync now.
    if (completed.length) await ctx.store.putBaselines(c.spaceId, completed).catch(() => undefined);
    return { ok: false, recorded: true, refresh: true, message: `The decision was recorded on the server, but this folder could not be updated: ${describe(e)}. Nothing in the folder was lost; run Sync now.` };
  }

  const where = r === "KEEP_BOTH" ? ` The other version is saved as "${res.siblingPath}".` : "";
  const done = r === "KEEP_LOCAL" ? "Kept this folder's version." : r === "KEEP_SERVER" ? "Kept the server's version." : "Kept both versions.";
  return { ok: true, recorded: true, refresh: true, message: `${c.path}: ${done}${where}` };
}
