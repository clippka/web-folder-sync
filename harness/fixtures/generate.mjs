#!/usr/bin/env node
// Reproducible fixture generator for the Step 1 browser feasibility harness.
//
//   node fixtures/generate.mjs --out <dir> [--files 10000] [--seed 1]
//        [--target-bytes N] [--excluded-files N] [--boundary] [--links]
//
// The same arguments always produce byte-identical output. A manifest with the
// SHA-256 and size of every included file is written to <out>/.fixture-manifest.json.
// "Included" means: not under a directory named in PRUNED_DIRS (the harness uses
// the same fixed list in place of the real ignore matcher built in Step 3).
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export const PRUNED_DIRS = ['node_modules', 'build', 'target', '.gradle', '.git', 'dist'];
export const LIMIT_BYTES = 104_857_600;

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const WORDS = ['fun', 'val', 'class', 'import', 'package', 'return', 'private', 'override', 'suspend', 'object',
  'interface', 'data', 'when', 'if', 'else', 'for', 'while', 'Sync', 'Folder', 'Entry', 'Space', 'Plan', 'Hash',
  'String', 'Int', 'List', 'Map', 'null', 'true', 'false', '=', '->', '{', '}', '(', ')', '//', '*', '"text"'];

function baseBlocks(rand) {
  const lines = [];
  let size = 0;
  while (size < 256 * 1024) {
    const n = 1 + Math.floor(rand() * 9);
    const parts = [];
    for (let i = 0; i < n; i++) parts.push(WORDS[Math.floor(rand() * WORDS.length)]);
    const indent = ' '.repeat(Math.floor(rand() * 4) * 2);
    const line = indent + parts.join(' ');
    lines.push(line);
    size += line.length + 1;
  }
  const lf = Buffer.from(lines.join('\n') + '\n', 'utf8');
  const crlf = Buffer.from(lines.join('\r\n') + '\r\n', 'utf8');
  const bin = Buffer.alloc(256 * 1024);
  for (let i = 0; i < bin.length; i++) bin[i] = Math.floor(rand() * 256);
  return { lf, crlf, bin };
}

function content(blocks, kind, header, size) {
  const out = Buffer.alloc(size);
  const h = Buffer.from(header, 'utf8');
  h.copy(out, 0, 0, Math.min(h.length, size));
  const src = blocks[kind];
  let pos = h.length;
  let off = (header.length * 7919) % src.length;
  while (pos < size) {
    const n = Math.min(size - pos, src.length - off);
    src.copy(out, pos, off, off + n);
    pos += n;
    off = 0;
  }
  return out;
}

function lognormalSizes(rand, count, targetBytes) {
  const sizes = new Array(count);
  const gauss = () => Math.sqrt(-2 * Math.log(rand() + 1e-12)) * Math.cos(2 * Math.PI * rand());
  let sum = 0;
  for (let i = 0; i < count; i++) {
    sizes[i] = Math.max(0, Math.floor(Math.exp(7.3 + 1.4 * gauss())));
    sum += sizes[i];
  }
  if (targetBytes) {
    const scale = targetBytes / sum;
    sum = 0;
    for (let i = 0; i < count; i++) {
      sizes[i] = Math.min(LIMIT_BYTES - 1, Math.floor(sizes[i] * scale));
      sum += sizes[i];
    }
    // Put the rounding remainder on the largest file(s) so the total is exact.
    let rem = targetBytes - sum;
    const order = sizes.map((s, i) => [s, i]).sort((a, b) => b[0] - a[0]);
    for (let k = 0; rem > 0 && k < order.length; k++) {
      const i = order[k][1];
      const add = Math.min(rem, LIMIT_BYTES - 1 - sizes[i]);
      sizes[i] += add;
      rem -= add;
    }
  }
  return sizes;
}

function writeAt(root, rel, data) {
  const full = path.join(root, ...rel.split('/'));
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, data);
}

function sha(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

function writeBigPattern(full, size, seedByte) {
  fs.mkdirSync(path.dirname(full), { recursive: true });
  const fd = fs.openSync(full, 'w');
  const hash = createHash('sha256');
  const chunk = Buffer.alloc(1 << 20);
  let written = 0;
  let idx = 0;
  while (written < size) {
    for (let i = 0; i < chunk.length; i += 4) chunk.writeUInt32LE(((idx * 2654435761) ^ (i * 40503) ^ seedByte) >>> 0, i);
    const n = Math.min(chunk.length, size - written);
    const view = chunk.subarray(0, n);
    fs.writeSync(fd, view);
    hash.update(view);
    written += n;
    idx++;
  }
  fs.closeSync(fd);
  return hash.digest('hex');
}

export function generate(opts) {
  const { out, files, seed, targetBytes, boundary, links } = opts;
  const excludedFiles = opts.excludedFiles ?? Math.min(files * 2, 50_000);
  const rand = mulberry32(seed);
  const blocks = baseBlocks(rand);
  fs.mkdirSync(out, { recursive: true });
  if (fs.readdirSync(out).length > 0) throw new Error(`Output directory ${out} is not empty`);

  // Nested ignore files (semantic correctness is Step 3; here they just exist and are included files).
  const ignoreFiles = {
    '.gitignore': 'node_modules/\nbuild/\ntarget/\n.gradle/\n',
    '.syncignore': '# local overrides\n!keep.txt\n',
    'web/.gitignore': 'dist/\n',
    'src/main/kotlin/.gitignore': 'generated/\n',
  };
  // Edge-case names and bytes that must round-trip exactly.
  const specials = [
    ['unicode/naïve-ünï-文件.txt', Buffer.from('unicode name\n')],
    ['unicode/emoji-🙂.txt', Buffer.from('emoji name\n')],
    ['bytes/crlf.txt', Buffer.from('a\r\nb\r\nc')],
    ['bytes/lone-cr.txt', Buffer.from('a\rb\rc\r')],
    ['bytes/bom-utf8.txt', Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('bom\n')])],
    ['bytes/nul-and-ff.bin', Buffer.from([0, 1, 2, 0xff, 0, 0xfe])],
    ['bytes/empty.txt', Buffer.alloc(0)],
    ['bytes/no-trailing-newline.txt', Buffer.from('last line without newline')],
    ['spaces/name with spaces.txt', Buffer.from('spaces\n')],
    ['spaces/.hidden-file', Buffer.from('hidden\n')],
  ];
  const extras = [...Object.entries(ignoreFiles).map(([rel, text]) => [rel, Buffer.from(text, 'utf8')]), ...specials];
  const extrasBytes = extras.reduce((n, [, b]) => n + b.length, 0);
  if (files <= extras.length) throw new Error(`--files must exceed ${extras.length} (fixed control and edge-case files)`);
  // --files and --target-bytes describe the whole included set, so the extras are carved out of the budget.
  const generated = files - extras.length;
  const sizes = lognormalSizes(rand, generated, targetBytes === undefined ? undefined : targetBytes - extrasBytes);
  const areas = ['src/main/kotlin/com/example', 'src/test/kotlin/com/example', 'web/src', 'docs', 'config', 'assets'];
  const exts = { src: ['kt', 'kt', 'java', 'ts'], web: ['ts', 'html', 'css', 'json'], docs: ['md', 'txt'], config: ['yaml', 'json', 'properties'], assets: ['png', 'bin', 'svg'] };
  const manifest = { generator: 1, seed, files: [], emptyDirs: [], excludedFiles: 0, boundary: [], links: [], limitBytes: LIMIT_BYTES };
  const used = new Set();

  for (let i = 0; i < generated; i++) {
    const area = areas[i % areas.length];
    const top = area.split('/')[0];
    const ext = exts[top][Math.floor(rand() * exts[top].length)];
    const dir = `${area}/p${(i >> 3) % 40}/m${(i >> 9) % 25}/s${(i >> 14) % 10}`;
    const rel = `${dir}/File${i}.${ext}`;
    const binary = ext === 'png' || ext === 'bin';
    const kind = binary ? 'bin' : i % 5 === 0 ? 'crlf' : 'lf';
    const data = content(blocks, kind, `// ${rel}\n`, sizes[i]);
    writeAt(out, rel, data);
    manifest.files.push({ path: rel, size: data.length, sha256: sha(data) });
    used.add(rel);
  }

  for (const [rel, data] of extras) {
    writeAt(out, rel, data);
    manifest.files.push({ path: rel, size: data.length, sha256: sha(data) });
  }

  for (const d of ['empty/a/b/c', 'empty/solo', 'src/main/kotlin/com/example/empty-package']) {
    fs.mkdirSync(path.join(out, ...d.split('/')), { recursive: true });
    manifest.emptyDirs.push(d);
  }

  // Large excluded trees. Their content is never listed in the manifest and must never be hashed.
  const excl = Buffer.from('excluded dependency content\n'.repeat(8));
  for (let i = 0; i < excludedFiles; i++) {
    const root = ['node_modules', 'build', 'target', '.gradle', 'web/dist'][i % 5];
    const rel = `${root}/pkg${i % 400}/lib/sub${(i >> 4) % 20}/f${i}.js`;
    writeAt(out, rel, excl);
    manifest.excludedFiles++;
  }
  writeAt(out, '.git/HEAD', Buffer.from('ref: refs/heads/main\n'));
  writeAt(out, '.git/objects/aa/bbbb', Buffer.from('fake object\n'));
  manifest.excludedFiles += 2;

  if (boundary) {
    for (const [name, size] of [['exact-limit.bin', LIMIT_BYTES], ['over-limit-by-one.bin', LIMIT_BYTES + 1]]) {
      const rel = `boundary/${name}`;
      const h = writeBigPattern(path.join(out, 'boundary', name), size, size & 0xff);
      manifest.boundary.push({ path: rel, size, sha256: h, overLimit: size > LIMIT_BYTES });
    }
  }

  if (links) {
    const base = path.join(out, '__links__');
    fs.mkdirSync(path.join(base, 'real-dir'), { recursive: true });
    fs.writeFileSync(path.join(base, 'real-dir', 'sentinel.txt'), 'sentinel: must survive link removal\n');
    fs.writeFileSync(path.join(base, 'real-file.txt'), 'real file\n');
    const make = (target, name, type) => {
      try {
        fs.symlinkSync(target, path.join(base, name), type);
        manifest.links.push({ name, target, type: type ?? 'file', created: true });
      } catch (e) {
        manifest.links.push({ name, target, type: type ?? 'file', created: false, error: e.code });
      }
    };
    make('real-file.txt', 'link-to-file');
    make('real-dir', 'link-to-dir', process.platform === 'win32' ? 'junction' : 'dir');
    make('does-not-exist', 'dangling-link');
    make('.', 'loop-link', process.platform === 'win32' ? 'junction' : 'dir');
    make('..', 'link-to-parent', process.platform === 'win32' ? 'junction' : 'dir');
  }

  manifest.counts = {
    includedFiles: manifest.files.length,
    includedBytes: manifest.files.reduce((s, f) => s + f.size, 0),
    emptyDirs: manifest.emptyDirs.length,
    excludedFiles: manifest.excludedFiles,
    prunedDirNames: PRUNED_DIRS,
  };
  fs.writeFileSync(path.join(out, '.fixture-manifest.json'), JSON.stringify(manifest));
  return manifest;
}

if (import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const { values } = parseArgs({
    options: {
      out: { type: 'string' }, files: { type: 'string', default: '1000' }, seed: { type: 'string', default: '1' },
      'target-bytes': { type: 'string' }, 'excluded-files': { type: 'string' },
      boundary: { type: 'boolean', default: false }, links: { type: 'boolean', default: false },
    },
  });
  if (!values.out) {
    console.error('usage: generate.mjs --out <dir> [--files N] [--seed N] [--target-bytes N] [--excluded-files N] [--boundary] [--links]');
    process.exit(2);
  }
  const t0 = Date.now();
  const m = generate({
    out: path.resolve(values.out), files: Number(values.files), seed: Number(values.seed),
    targetBytes: values['target-bytes'] ? Number(values['target-bytes']) : undefined,
    excludedFiles: values['excluded-files'] !== undefined ? Number(values['excluded-files']) : undefined,
    boundary: values.boundary, links: values.links,
  });
  console.log(JSON.stringify(m.counts), `in ${Date.now() - t0} ms`);
}
