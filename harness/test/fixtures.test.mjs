import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { generate, PRUNED_DIRS } from '../fixtures/generate.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'fsync-fixture-'));

function snapshot(dir) {
  const out = {};
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) { out[path.relative(dir, p) + '/'] = null; walk(p); }
      else out[path.relative(dir, p)] = createHash('sha256').update(fs.readFileSync(p)).digest('hex');
    }
  })(dir);
  return out;
}

test('same arguments produce identical trees; manifest matches disk', () => {
  const a = tmp(), b = tmp();
  const ma = generate({ out: a, files: 300, seed: 7, excludedFiles: 100 });
  generate({ out: b, files: 300, seed: 7, excludedFiles: 100 });
  assert.deepEqual(snapshot(a), snapshot(b));
  for (const f of ma.files) {
    const buf = fs.readFileSync(path.join(a, ...f.path.split('/')));
    assert.equal(buf.length, f.size);
    assert.equal(createHash('sha256').update(buf).digest('hex'), f.sha256);
  }
  fs.rmSync(a, { recursive: true }); fs.rmSync(b, { recursive: true });
});

test('included set excludes pruned directories; empty dirs and excluded count recorded', () => {
  const d = tmp();
  const m = generate({ out: d, files: 200, seed: 3, excludedFiles: 150 });
  for (const f of m.files) assert.ok(!f.path.split('/').some((s) => PRUNED_DIRS.includes(s)), f.path);
  assert.equal(m.counts.excludedFiles, 152);
  assert.equal(m.emptyDirs.length, 3);
  for (const e of m.emptyDirs) assert.deepEqual(fs.readdirSync(path.join(d, ...e.split('/'))), [], e);
  fs.rmSync(d, { recursive: true });
});

test('--files and --target-bytes describe the whole included set', () => {
  const d = tmp();
  const m = generate({ out: d, files: 500, seed: 5, excludedFiles: 0, targetBytes: 20_000_000 });
  assert.equal(m.counts.includedFiles, 500);
  assert.equal(m.counts.includedBytes, 20_000_000);
  assert.ok(Math.max(...m.files.map((f) => f.size)) < 104_857_600);
  // On-disk reality matches the manifest (excluding the manifest file and the fake .git tree).
  let n = 0, bytes = 0;
  (function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { if (e.name !== '.git') walk(p); } else if (e.name !== '.fixture-manifest.json') { n++; bytes += fs.statSync(p).size; }
    }
  })(d);
  assert.equal(n, 500);
  assert.equal(bytes, 20_000_000);
  fs.rmSync(d, { recursive: true });
});

test('too few files is rejected', () => {
  assert.throws(() => generate({ out: tmp(), files: 5, seed: 1, excludedFiles: 0 }), /must exceed/);
});
