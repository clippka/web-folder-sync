// Folder bindings: choosing a folder, permission checks, overlap detection and the exclusive per-space lock.
import type { BindingRecord } from "../idb";
import type { RootHandleLike } from "../fs/types";

export function fileSystemAccessSupported(): boolean {
  return typeof window !== "undefined" && "showDirectoryPicker" in window && window.isSecureContext;
}

/** Opens the directory picker. Must be called from a user gesture. Resolves null when the user cancels. */
export async function pickFolder(): Promise<RootHandleLike | null> {
  try {
    const picker = (window as unknown as { showDirectoryPicker(o?: { mode?: "read" | "readwrite"; id?: string }): Promise<RootHandleLike> }).showDirectoryPicker;
    return await picker.call(window, { mode: "readwrite", id: "foldersync" });
  } catch (e) {
    if ((e as Error).name === "AbortError") return null;
    throw e;
  }
}

export type PermissionState = "granted" | "denied" | "prompt";

export async function queryWritePermission(handle: RootHandleLike): Promise<PermissionState> {
  return handle.queryPermission({ mode: "readwrite" });
}

/** May show a browser prompt, so call it only from a click. */
export async function requestWritePermission(handle: RootHandleLike): Promise<PermissionState> {
  return handle.requestPermission({ mode: "readwrite" });
}

/** True when the handle still points at a readable directory (the folder may have been moved, deleted or unplugged). */
export async function folderReachable(handle: RootHandleLike): Promise<boolean> {
  try {
    const it = handle.entries()[Symbol.asyncIterator]();
    await it.next();
    return true;
  } catch { return false; }
}

export type Overlap = { binding: BindingRecord; relation: "same" | "inside" | "contains" };

/**
 * Rejects binding a folder that is, contains or lies inside a folder already bound in this browser profile. Two
 * bindings over overlapping trees would sync the same files under two identities. Detection is best effort: it covers
 * this profile only (see the separate-profile limitation in the product help).
 */
export async function findOverlap(candidate: RootHandleLike, existing: BindingRecord[], ignoreSpaceId?: string): Promise<Overlap | null> {
  for (const b of existing) {
    if (b.spaceId === ignoreSpaceId) continue;
    try {
      if (await candidate.isSameEntry(b.handle)) return { binding: b, relation: "same" };
      if ((await b.handle.resolve(candidate)) !== null) return { binding: b, relation: "inside" };
      if ((await candidate.resolve(b.handle)) !== null) return { binding: b, relation: "contains" };
    } catch { /* an unreadable existing handle cannot be compared; do not block on it */ }
  }
  return null;
}

export const LOCK_BUSY = Symbol("lock-busy");

/**
 * Runs [fn] holding an exclusive cross-tab lock, or returns LOCK_BUSY immediately when another tab of this browser
 * profile already syncs the same binding. The lock is released when [fn] settles or the page goes away.
 */
export async function withBindingLock<T>(name: string, fn: () => Promise<T>): Promise<T | typeof LOCK_BUSY> {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  if (!locks) return fn(); // Without Web Locks the in-page guard still prevents a second run in this page.
  return locks.request(name, { ifAvailable: true }, async (lock) => (lock ? fn() : LOCK_BUSY)) as Promise<T | typeof LOCK_BUSY>;
}

export function bindingLockName(userId: string, spaceId: string): string {
  return `foldersync:${userId}:${spaceId}`;
}
