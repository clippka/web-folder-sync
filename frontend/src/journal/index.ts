// Recovery of local mutations that a previous run did not finish (browser closed, tab killed, power loss, crash).
//
// Before a file is replaced the run writes a journal entry holding the content it expected to find and the content it is
// about to write. The entry is removed only after the new baseline is stored. When the user explicitly starts the next
// run, every leftover entry is settled by looking at the bytes actually on disk, never by assuming:
//
//   * disk holds the intended content  -> the write completed; record its baseline (no acknowledgement is invented:
//                                         the server learns about it through the normal plan, which will adopt it)
//   * disk holds the expected content  -> the write never happened; keep the old baseline
//   * disk holds anything else         -> unknown; drop the baseline so the planner compares both sides with no history,
//                                         which can only adopt identical content or keep both variants as a conflict.
//
// Nothing here deletes or overwrites a user file.
import type { Hasher } from "../hash/pool";
import type { BaselineRecord, JournalEntry, SyncStore } from "../idb";
import type { DirHandleLike, FileHandleLike } from "../fs/types";

export type LocalView =
  | { kind: "absent" }
  | { kind: "file"; handle: FileHandleLike; file: File }
  | { kind: "directory" }
  | { kind: "blocked"; why: string };

export type RecoveryResult = "completed" | "untouched" | "unknown";

export interface RecoveryReport {
  completed: string[];
  untouched: string[];
  /** Paths whose local content matches neither version; they are compared again from scratch and may become conflicts. */
  unknown: string[];
}

export interface RecoveryContext {
  store: SyncStore;
  hasher: Hasher;
  root: DirHandleLike;
  spaceId: string;
  signal: AbortSignal;
  lookup(root: DirHandleLike, path: string): Promise<LocalView>;
}

export async function recoverInterrupted(ctx: RecoveryContext): Promise<RecoveryReport> {
  const report: RecoveryReport = { completed: [], untouched: [], unknown: [] };
  const entries = await ctx.store.journal(ctx.spaceId);
  for (const entry of entries) {
    if (ctx.signal.aborted) break;
    const result = await settle(ctx, entry);
    report[result.result].push(entry.path);
    if (result.baseline === "drop") await ctx.store.deleteBaselines(ctx.spaceId, [entry.path]);
    else if (result.baseline) await ctx.store.putBaselines(ctx.spaceId, [result.baseline]);
    await ctx.store.deleteJournal(ctx.spaceId, [entry.path]);
  }
  return report;
}

async function settle(ctx: RecoveryContext, e: JournalEntry): Promise<{ result: RecoveryResult; baseline: BaselineRecord | "drop" | null }> {
  const local = await ctx.lookup(ctx.root, e.path);
  if (local.kind === "absent") {
    // Nothing is there. If a file was expected the old baseline no longer describes the folder.
    return e.expected.type === "ABSENT" ? { result: "untouched", baseline: null } : { result: "unknown", baseline: "drop" };
  }
  if (local.kind !== "file") return { result: "unknown", baseline: "drop" };

  const { size, lastModified } = local.file;
  if (size === e.intended.size) {
    const sha = await ctx.hasher.hash(local.file, ctx.signal);
    if (sha === e.intended.sha256) return { result: "completed", baseline: { path: e.path, type: "FILE", sha256: sha, size, mtime: lastModified } };
  }
  if (e.expected.type === "FILE" && size === e.expected.size) {
    const sha = await ctx.hasher.hash(local.file, ctx.signal);
    if (sha === e.expected.sha256) return { result: "untouched", baseline: null };
  }
  return { result: "unknown", baseline: "drop" };
}

/** True when a previous run left work behind, so the next click is a resume rather than a fresh start. */
export async function hasPendingRecovery(store: SyncStore, spaceId: string): Promise<boolean> {
  return (await store.journal(spaceId)).length > 0 || (await store.uploadRecords(spaceId)).length > 0;
}

export function describeRecovery(r: RecoveryReport): string[] {
  const notes: string[] = [];
  const n = (xs: string[]) => `${xs.length} file${xs.length === 1 ? "" : "s"}`;
  if (r.completed.length) notes.push(`Resumed: ${n(r.completed)} that a previous run had finished writing ${r.completed.length === 1 ? "was" : "were"} verified and recorded.`);
  if (r.untouched.length) notes.push(`Resumed: ${n(r.untouched)} that a previous run had not started writing ${r.untouched.length === 1 ? "is" : "are"} unchanged and will be planned again.`);
  if (r.unknown.length) notes.push(`${n(r.unknown)} did not match either version after an interrupted run (${r.unknown.slice(0, 3).join(", ")}${r.unknown.length > 3 ? ", ..." : ""}). Nothing was overwritten; they are compared again and kept as conflicts if they differ.`);
  return notes;
}
