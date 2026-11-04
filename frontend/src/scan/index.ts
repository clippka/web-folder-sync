// Enumeration of the bound folder. Ignored directories are pruned before their contents are read or hashed, ignore
// files are read directory by directory as the scan descends, and every failure is recorded explicitly: a path that
// could not be read is never silently "absent", because absence is what deletion planning is built on.
import { IgnorePolicy, type IgnoreDecision } from "../policy/ignore-policy";
import type { Hasher } from "../hash/pool";
import { isCancelled } from "../hash/pool";
import { isNotFound, type DirHandleLike, type FileHandleLike } from "../fs/types";
import type { BaselineRecord } from "../idb";

/** What a file hashed to the last time its size and mtime were verified. */
export interface StatCache { size: number; mtime: number; sha256: string }

export interface ScannedEntry {
  path: string;
  type: "FILE" | "DIRECTORY";
  size?: number;
  sha256?: string;
  mtime?: number;
  /** Larger than the per-file limit: not hashed, never transferred. */
  oversized?: boolean;
}

export interface IgnoredPath { path: string; directory: boolean; rule?: string; source?: string; builtin?: boolean }
export interface ScanProblem { path: string; kind: "directory" | "file"; message: string }

export interface ScanResult {
  entries: Map<string, ScannedEntry>;
  ignored: IgnoredPath[];
  ignoredCount: number;
  problems: ScanProblem[];
  policy: IgnorePolicy;
  /** False when anything could not be enumerated or read: no deletion may follow from this scan. */
  complete: boolean;
  fileCount: number;
  directoryCount: number;
  hashedCount: number;
  reusedHashCount: number;
}

export interface ScanProgress { directories: number; files: number; hashed: number; current?: string }

export interface ScanOptions {
  root: DirHandleLike;
  hasher: Hasher;
  maxFileSizeBytes: number;
  /** Verified hashes by path; reused only when size and mtime are unchanged. */
  cache: ReadonlyMap<string, StatCache>;
  signal: AbortSignal;
  onProgress?: (p: ScanProgress) => void;
  /** Concurrent file reads queued to the hash pool. */
  concurrency?: number;
  maxIgnoredListed?: number;
}

/** A SHA-256 placeholder for oversized files: they are never hashed (reading 5 GB to refuse it would be absurd). The
 *  server suspends oversized paths before it looks at the hash, so the value is never compared with anything. */
export const OVERSIZED_PLACEHOLDER_SHA = "0".repeat(64);

const CONTROL_NAMES = [".gitignore", ".syncignore"] as const;

async function readControlFile(dir: DirHandleLike, name: string, problems: ScanProblem[], path: string): Promise<string | null> {
  try {
    const fh = await dir.getFileHandle(name);
    return await (await fh.getFile()).text();
  } catch (e) {
    if (isNotFound(e)) return null;
    problems.push({ path, kind: "file", message: `Could not read ${name}: ${(e as Error).message}` });
    return null;
  }
}

export async function scanFolder(opts: ScanOptions): Promise<ScanResult> {
  const { root, hasher, signal } = opts;
  const policy = new IgnorePolicy();
  const entries = new Map<string, ScannedEntry>();
  const ignored: IgnoredPath[] = [];
  const problems: ScanProblem[] = [];
  const maxListed = opts.maxIgnoredListed ?? 2000;
  let ignoredCount = 0, directoryCount = 0, fileCount = 0, hashedCount = 0, reusedHashCount = 0;
  const concurrency = Math.max(1, opts.concurrency ?? 8);
  const inflight = new Set<Promise<void>>();
  let fatal: unknown = null;

  const progress = (current?: string) => opts.onProgress?.({ directories: directoryCount, files: fileCount, hashed: hashedCount + reusedHashCount, ...(current ? { current } : {}) });

  const note = (path: string, directory: boolean, d: IgnoreDecision) => {
    ignoredCount++;
    if (ignored.length >= maxListed) return;
    ignored.push({
      path, directory,
      ...(d.builtin ? { builtin: true } : {}),
      ...(d.rule ? { rule: d.rule.text, source: `${d.rule.sourceFile}:${d.rule.line}` } : {}),
    });
  };

  async function hashFile(path: string, fh: FileHandleLike): Promise<void> {
    try {
      const file = await fh.getFile();
      if (file.size > opts.maxFileSizeBytes) {
        entries.set(path, { path, type: "FILE", size: file.size, sha256: OVERSIZED_PLACEHOLDER_SHA, mtime: file.lastModified, oversized: true });
        return;
      }
      const cached = opts.cache.get(path);
      let sha256: string;
      if (cached && cached.size === file.size && cached.mtime === file.lastModified) { sha256 = cached.sha256; reusedHashCount++; }
      else { sha256 = await hasher.hash(file, signal); hashedCount++; }
      entries.set(path, { path, type: "FILE", size: file.size, sha256, mtime: file.lastModified });
    } catch (e) {
      if (isCancelled(e) || signal.aborted) { fatal ??= e; return; }
      problems.push({ path, kind: "file", message: (e as Error).message });
    } finally { progress(path); }
  }

  const queue: Array<{ dir: DirHandleLike; path: string }> = [{ dir: root, path: "" }];
  directoryCount = 0;
  while (queue.length > 0) {
    if (signal.aborted) throw Object.assign(new Error("cancelled"), { cancelled: true });
    if (fatal) break;
    const { dir, path: dirPath } = queue.shift()!;

    // Rule files of this directory apply to everything below it, so they are read before the listing is used.
    const gi = await readControlFile(dir, CONTROL_NAMES[0], problems, dirPath === "" ? CONTROL_NAMES[0] : `${dirPath}/${CONTROL_NAMES[0]}`);
    const si = await readControlFile(dir, CONTROL_NAMES[1], problems, dirPath === "" ? CONTROL_NAMES[1] : `${dirPath}/${CONTROL_NAMES[1]}`);
    if (gi !== null || si !== null) policy.setDirectory(dirPath, { gitignore: gi, syncignore: si });

    const listing: Array<[string, FileHandleLike | DirHandleLike]> = [];
    try {
      for await (const item of dir.entries()) listing.push(item);
    } catch (e) {
      // The subtree could not be enumerated: the scan is incomplete and nothing under it may be inferred.
      problems.push({ path: dirPath, kind: "directory", message: (e as Error).message });
      continue;
    }
    listing.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));

    for (const [name, handle] of listing) {
      const path = dirPath === "" ? name : `${dirPath}/${name}`;
      const isDir = handle.kind === "directory";
      const decision = policy.evaluate(path, isDir);
      if (decision.ignored) { note(path, isDir, decision); continue; }
      if (handle.kind === "directory") {
        directoryCount++;
        entries.set(path, { path, type: "DIRECTORY" });
        queue.push({ dir: handle, path });
        progress(path);
      } else {
        fileCount++;
        while (inflight.size >= concurrency) await Promise.race(inflight);
        if (fatal) break;
        const p: Promise<void> = hashFile(path, handle).finally(() => inflight.delete(p));
        inflight.add(p);
      }
    }
  }
  await Promise.allSettled(inflight);
  if (fatal || signal.aborted) throw fatal ?? Object.assign(new Error("cancelled"), { cancelled: true });

  return {
    entries, ignored, ignoredCount, problems, policy,
    complete: problems.length === 0,
    fileCount, directoryCount, hashedCount, reusedHashCount,
  };
}

// ---- plan input ----------------------------------------------------------------------------------------------

export interface PlanEntry {
  path: string;
  baseline?: { type: "FILE" | "DIRECTORY"; sha256?: string; size?: number };
  local?: { type: "FILE" | "DIRECTORY"; sha256?: string; size?: number };
  unreadable?: boolean;
  reincluded?: boolean;
}

export interface PlanInput {
  entries: PlanEntry[];
  /** Baseline paths that this scan did not look at (ignored or unreadable): flagged suspended once the plan is made. */
  suspendedPaths: string[];
  /** Baselines whose suspension ends in this scan. */
  reincludedPaths: string[];
}

function underAny(path: string, dirs: string[]): boolean {
  return dirs.some((d) => d === "" || path === d || path.startsWith(d + "/"));
}

function baselineState(b: BaselineRecord): PlanEntry["baseline"] {
  return b.type === "DIRECTORY" ? { type: "DIRECTORY" } : { type: "FILE", ...(b.sha256 ? { sha256: b.sha256 } : {}), ...(b.size !== undefined ? { size: b.size } : {}) };
}

/**
 * Turns a scan plus the stored baselines into the manifest sent to the server.
 *
 * The rule that keeps this safe: a baseline path the scan did not observe is reported "absent" only when the scan
 * actually listed its parent and it is simply gone. If the local policy skipped it (a rule may exist locally that the
 * server has not seen yet) or its directory could not be read, it is reported unreadable, which blocks the path and
 * never proposes a deletion.
 */
export function buildPlanInput(scan: ScanResult, baselines: ReadonlyMap<string, BaselineRecord>): PlanInput {
  const entries: PlanEntry[] = [];
  const suspended: string[] = [];
  const reincluded: string[] = [];
  const unreadableDirs = scan.problems.filter((p) => p.kind === "directory").map((p) => p.path);

  for (const e of scan.entries.values()) {
    const b = baselines.get(e.path);
    const plan: PlanEntry = {
      path: e.path,
      local: e.type === "DIRECTORY" ? { type: "DIRECTORY" } : { type: "FILE", sha256: e.sha256!, size: e.size! },
    };
    if (b) plan.baseline = baselineState(b);
    if (b?.suspended && !e.oversized) { plan.reincluded = true; reincluded.push(e.path); }
    if (b && e.oversized) suspended.push(e.path);
    entries.push(plan);
  }
  const fileProblems = new Set(scan.problems.filter((p) => p.kind === "file").map((p) => p.path));
  for (const [path, b] of baselines) {
    if (scan.entries.has(path)) continue;
    const plan: PlanEntry = { path, baseline: baselineState(b) };
    const skipped = scan.policy.evaluate(path, b.type === "DIRECTORY").ignored;
    if (skipped || underAny(path, unreadableDirs) || fileProblems.has(path)) {
      plan.unreadable = true;
      if (skipped) suspended.push(path);
    } else if (b.suspended) {
      plan.reincluded = true;
      reincluded.push(path);
    }
    entries.push(plan);
  }
  entries.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { entries, suspendedPaths: suspended, reincludedPaths: reincluded };
}
