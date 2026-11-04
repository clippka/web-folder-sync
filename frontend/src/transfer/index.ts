// Uploads (resumable fixed-size chunks) and downloads (streamed, hashed before the replacement is committed).
import { Api, ApiError, newKey } from "../api/client";
import type { Hasher } from "../hash/pool";
import { Sha256 } from "../hash/sha256";
import { isNotFound, resolveDir, splitPath, type DirHandleLike } from "../fs/types";

interface UploadBegin {
  uploadId: string; state: string; size: number; chunkSize: number; chunkCount: number; receivedChunks: number[]; consumed?: boolean;
}

const MAX_ATTEMPTS = 3;

async function withRetry<T>(fn: () => Promise<T>, signal: AbortSignal): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try { return await fn(); }
    catch (e) {
      const retryable = e instanceof ApiError && (e.retryable || e.status === 0);
      if (!retryable || attempt >= MAX_ATTEMPTS || signal.aborted) throw e;
      await new Promise((r) => setTimeout(r, 250 * attempt * attempt));
    }
  }
}

export interface UploadOptions {
  /** Idempotency key of the begin request. Reusing a key continues the same upload after an interruption. */
  key?: string;
  /** The key came from an earlier attempt: check the server's view of the upload before sending anything. */
  resume?: boolean;
}

/** The upload a resume pointed at is gone, expired, failed or already used by a commit; begin a new one. */
export function isStaleUpload(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 404 || e.status === 410 || e.code === "upload_expired");
}

/**
 * Uploads [file] and returns the completed upload id. The server verifies size and SHA-256 over the assembled bytes
 * before publishing, so a file that changed while it was being read is rejected rather than stored under the wrong
 * digest. Chunks are slices of the file: memory use is one chunk. Chunks the server already holds are skipped, and a
 * network failure or cancellation leaves the staged chunks in place so an explicit resume does not start over; only a
 * definite server rejection discards them.
 */
export async function uploadFile(
  api: Api, spaceId: string, file: Blob, sha256: string, signal: AbortSignal,
  onBytes?: (sent: number, total: number) => void, opts: UploadOptions = {},
): Promise<string> {
  const key = opts.key ?? newKey();
  let begin = await withRetry(() => api.post<UploadBegin>(`/api/spaces/${spaceId}/uploads`, { size: file.size, sha256 }, { headers: { "Idempotency-Key": key }, signal }), signal);
  if (opts.resume) {
    // A replayed begin returns the original answer, which cannot know what arrived since. Ask the upload itself.
    const status = await withRetry(() => api.get<UploadBegin>(`/api/uploads/${begin.uploadId}`, undefined, signal), signal);
    if (status.consumed || !["OPEN", "COMPLETE"].includes(status.state)) {
      throw new ApiError(410, "upload_expired", "The earlier upload can no longer be continued", false);
    }
    begin = { ...begin, ...status };
    if (status.state === "COMPLETE") { onBytes?.(file.size, file.size); return begin.uploadId; }
  }
  const have = new Set(begin.receivedChunks);
  let sent = 0;
  try {
    for (let i = 0; i < begin.chunkCount; i++) {
      if (signal.aborted) throw Object.assign(new Error("cancelled"), { cancelled: true });
      const from = i * begin.chunkSize;
      const to = Math.min(file.size, from + begin.chunkSize);
      if (!have.has(i)) await withRetry(() => api.put(`/api/uploads/${begin.uploadId}/chunks/${i}`, file.slice(from, to), { signal }), signal);
      sent = to;
      onBytes?.(sent, file.size);
    }
    await withRetry(() => api.post(`/api/uploads/${begin.uploadId}/complete`, undefined, { signal }), signal);
  } catch (e) {
    // A definite rejection (digest, size, quota, limits) cannot be resumed: release the staged bytes and the quota
    // reservation now. Cancellation and connection loss keep them for the next explicit resume (they expire anyway).
    const definite = e instanceof ApiError && e.status !== 0 && !e.retryable && !isStaleUpload(e);
    if (definite) api.delete(`/api/uploads/${begin.uploadId}`).catch(() => undefined);
    throw e;
  }
  return begin.uploadId;
}

export interface DownloadResult { size: number; mtime: number; sha256: string }

/**
 * Streams a blob into the local file. Bytes are hashed as they arrive and the write is aborted unless size and
 * SHA-256 match the plan, so a corrupt or truncated transfer never replaces a file. `createWritable` stages into a
 * swap file that only replaces the target on close(). After close the file is re-read and hashed again: that, not
 * the transfer, is what a baseline records.
 */
export async function downloadFile(
  api: Api, hasher: Hasher, root: DirHandleLike, path: string, spaceId: string, blobId: string,
  expected: { sha256: string; size: number }, signal: AbortSignal, onBytes?: (n: number, total: number) => void,
  /** Runs after the full content is verified and immediately before it replaces the file; throwing aborts the write. */
  beforeReplace?: () => Promise<void>,
): Promise<DownloadResult> {
  const { parent, name } = splitPath(path);
  const dir = await resolveDir(root, parent, true);
  let existed = true;
  try { await dir.getFileHandle(name); } catch (e) { if (isNotFound(e)) existed = false; else throw e; }
  const fh = await dir.getFileHandle(name, { create: true });
  const cleanupNew = async () => { if (!existed) await dir.removeEntry(name).catch(() => undefined); };

  let writable;
  try {
    const res = await api.stream(`/api/spaces/${spaceId}/blobs/${blobId}/content`, signal);
    if (!res.body) throw new Error("The server sent no content");
    writable = await fh.createWritable();
    const sha = new Sha256();
    let received = 0;
    const reader = res.body.getReader();
    for (;;) {
      if (signal.aborted) throw Object.assign(new Error("cancelled"), { cancelled: true });
      const { done, value } = await reader.read();
      if (done) break;
      received += value.length;
      if (received > expected.size) throw new Error(`Received more than the planned ${expected.size} bytes`);
      sha.update(value);
      await writable.write(value);
      onBytes?.(received, expected.size);
    }
    if (received !== expected.size) throw new Error(`Received ${received} bytes, expected ${expected.size}`);
    const got = sha.digestHex();
    if (got !== expected.sha256) throw new Error("Downloaded content does not match the planned SHA-256");
    await beforeReplace?.();
    await writable.close();
    writable = undefined;
  } catch (e) {
    if (writable) await writable.abort().catch(() => undefined);
    await cleanupNew();
    throw e;
  }

  const written = await fh.getFile();
  const actual = await hasher.hash(written, signal);
  if (actual !== expected.sha256 || written.size !== expected.size) {
    // The replacement already happened, so the caller must keep its journal entry for recovery.
    throw Object.assign(new Error("The file on disk does not match after writing (was it changed by another program?)"), { localChanged: true });
  }
  return { size: written.size, mtime: written.lastModified, sha256: actual };
}

/** Creates a directory (and parents). Existing directories are left alone. */
export async function ensureDirectory(root: DirHandleLike, path: string): Promise<void> {
  await resolveDir(root, path.split("/"), true);
}
