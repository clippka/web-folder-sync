// Deletion review model. Deletions are never inferred silently: the plan lists proposals, this module groups them for
// the person to read, and execution stays disabled until the server's safety gate is open (Step 8).
import type { Limits, WireOp } from "./run";

export interface DeletionItem { path: string; directory: boolean }

export interface DeletionReview {
  /** Files that would be deleted from the server (and, on their next sync, from every other connected folder). */
  fromServer: DeletionItem[];
  /** Files that would be deleted from this folder because they were deleted elsewhere. */
  fromThisFolder: DeletionItem[];
  fileCount: number;
  directoryCount: number;
  /** Large batches are called out: at least `warnCount` files, or more than `warnPercent` of the active files. */
  emphasized: boolean;
  reason: string | null;
  /** False while the server refuses deletions. The review is still shown so nothing is hidden. */
  executionEnabled: boolean;
}

export const DEFAULT_WARN_COUNT = 100;
export const DEFAULT_WARN_PERCENT = 20;

export function reviewDeletions(ops: readonly WireOp[], opts: { activeFiles: number; limits: Pick<Limits, "deletionEnabled" | "deletionWarnCount" | "deletionWarnPercent"> }): DeletionReview | null {
  const fromServer: DeletionItem[] = [];
  const fromThisFolder: DeletionItem[] = [];
  for (const op of ops) {
    if (op.kind !== "PROPOSE_SERVER_DELETION" && op.kind !== "PROPOSE_LOCAL_DELETION") continue;
    const item = { path: op.path, directory: op.expected?.type === "DIRECTORY" };
    (op.kind === "PROPOSE_SERVER_DELETION" ? fromServer : fromThisFolder).push(item);
  }
  const all = [...fromServer, ...fromThisFolder];
  if (!all.length) return null;
  const fileCount = all.filter((i) => !i.directory).length;
  const warnCount = opts.limits.deletionWarnCount ?? DEFAULT_WARN_COUNT;
  const warnPercent = opts.limits.deletionWarnPercent ?? DEFAULT_WARN_PERCENT;
  let reason: string | null = null;
  if (fileCount >= warnCount) reason = `${fileCount} files is ${warnCount} or more`;
  else if (opts.activeFiles > 0 && fileCount * 100 > warnPercent * opts.activeFiles) reason = `${fileCount} of ${opts.activeFiles} files is more than ${warnPercent}%`;
  return {
    fromServer, fromThisFolder, fileCount, directoryCount: all.length - fileCount,
    emphasized: reason !== null, reason, executionEnabled: opts.limits.deletionEnabled === true,
  };
}
