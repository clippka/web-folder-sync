// The slice of the File System Access API the coordinator uses. Real handles satisfy these structurally; tests
// supply in-memory fakes. Nothing here follows links: the API does not expose them, so links are an unsupported-path
// concern reported by the Step 1 compatibility matrix.
export interface WritableLike {
  write(data: Uint8Array | Blob): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

export interface FileHandleLike {
  readonly kind: "file";
  readonly name: string;
  getFile(): Promise<File>;
  createWritable(opts?: { keepExistingData?: boolean }): Promise<WritableLike>;
}

export interface DirHandleLike {
  readonly kind: "directory";
  readonly name: string;
  entries(): AsyncIterable<[string, FileHandleLike | DirHandleLike]>;
  getDirectoryHandle(name: string, opts?: { create?: boolean }): Promise<DirHandleLike>;
  getFileHandle(name: string, opts?: { create?: boolean }): Promise<FileHandleLike>;
  removeEntry(name: string, opts?: { recursive?: boolean }): Promise<void>;
}

/** Additional capabilities of a user-selected root handle. */
export interface RootHandleLike extends DirHandleLike {
  isSameEntry(other: unknown): Promise<boolean>;
  resolve(possibleDescendant: unknown): Promise<string[] | null>;
  queryPermission(desc: { mode: "read" | "readwrite" }): Promise<"granted" | "denied" | "prompt">;
  requestPermission(desc: { mode: "read" | "readwrite" }): Promise<"granted" | "denied" | "prompt">;
}

export function isNotFound(e: unknown): boolean {
  const name = (e as { name?: string } | null)?.name;
  return name === "NotFoundError" || name === "TypeMismatchError";
}

/** Resolves a directory by relative segments; never creates unless asked. */
export async function resolveDir(root: DirHandleLike, segments: readonly string[], create: boolean): Promise<DirHandleLike> {
  let cur = root;
  for (const s of segments) cur = await cur.getDirectoryHandle(s, { create });
  return cur;
}

export function splitPath(path: string): { parent: string[]; name: string } {
  const parts = path.split("/");
  return { parent: parts.slice(0, -1), name: parts[parts.length - 1]! };
}
