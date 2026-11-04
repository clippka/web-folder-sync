// Test support: bundle a TypeScript module with esbuild and import it, plus an in-memory File System Access fake.
import { build } from "esbuild";
import { createHash } from "node:crypto";

export async function load(entry) {
  const out = await build({ entryPoints: [entry], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
  return import("data:text/javascript;base64," + Buffer.from(out.outputFiles[0].text).toString("base64"));
}

export const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");
export const utf8 = (s) => new TextEncoder().encode(s);

function domError(name, message) { return Object.assign(new Error(message), { name }); }

export class FakeFile {
  kind = "file";
  constructor(name, bytes = new Uint8Array(), mtime = 1000) { this.name = name; this.bytes = bytes; this.mtime = mtime; }
  async getFile() { return new File([this.bytes], this.name, { lastModified: this.mtime }); }
  async createWritable() {
    const chunks = [];
    const self = this;
    return {
      async write(d) { chunks.push(d instanceof Uint8Array ? d.slice() : new Uint8Array(await d.arrayBuffer())); },
      async close() { self.bytes = Buffer.concat(chunks); self.mtime += 1; },
      async abort() { chunks.length = 0; },
    };
  }
}

export class FakeDir {
  kind = "directory";
  constructor(name) { this.name = name; this.children = new Map(); this.failListing = false; }
  async *entries() {
    if (this.failListing) throw domError("NotAllowedError", "listing denied");
    for (const [n, h] of this.children) yield [n, h];
  }
  async getDirectoryHandle(name, opts = {}) {
    const c = this.children.get(name);
    if (c) { if (c.kind !== "directory") throw domError("TypeMismatchError", "not a directory"); return c; }
    if (!opts.create) throw domError("NotFoundError", "missing");
    const d = new FakeDir(name); this.children.set(name, d); return d;
  }
  async getFileHandle(name, opts = {}) {
    const c = this.children.get(name);
    if (c) { if (c.kind !== "file") throw domError("TypeMismatchError", "not a file"); return c; }
    if (!opts.create) throw domError("NotFoundError", "missing");
    const f = new FakeFile(name); this.children.set(name, f); return f;
  }
  async removeEntry(name, opts = {}) {
    const c = this.children.get(name);
    if (!c) throw domError("NotFoundError", "missing");
    if (c.kind === "directory" && c.children.size && !opts.recursive) throw domError("InvalidModificationError", "not empty");
    this.removeLog?.push(name);
    this.children.delete(name);
  }

  // test conveniences
  put(path, content, mtime) {
    const parts = path.split("/");
    let d = this;
    for (const p of parts.slice(0, -1)) { if (!d.children.has(p)) d.children.set(p, new FakeDir(p)); d = d.children.get(p); }
    const bytes = typeof content === "string" ? utf8(content) : content;
    d.children.set(parts.at(-1), new FakeFile(parts.at(-1), bytes, mtime ?? 1000));
  }
  mkdir(path) {
    let d = this;
    for (const p of path.split("/")) { if (!d.children.has(p)) d.children.set(p, new FakeDir(p)); d = d.children.get(p); }
    return d;
  }
  read(path) {
    let h = this;
    for (const p of path.split("/")) h = h.children.get(p);
    return h?.bytes;
  }
  has(path) {
    let h = this;
    for (const p of path.split("/")) { h = h.children?.get(p); if (!h) return false; }
    return true;
  }
}

/** Hashes with node's crypto and counts calls, so tests can prove what was (not) read. */
export class CountingHasher {
  calls = [];
  async hash(file) { this.calls.push(file.name); return sha(Buffer.from(await file.arrayBuffer())); }
  terminate() {}
}
