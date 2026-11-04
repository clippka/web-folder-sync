import { Checks, attempt, freshScratch, writeBytes, readBytes, bytesEqual, listNames } from '../lib/util.js';

export const entries = {
  id: 'entries',
  title: 'Empty directories, byte fidelity, name collisions, invalid names, path depth',
  needsRoot: true,
  spec: '§5 directories and bytes; §12 portable path policy; acceptance 12, 14, 21',
  async run(ctx) {
    const c = new Checks();
    const data = {};
    const dir = await freshScratch(ctx.root);

    // Nested empty directories survive enumeration.
    let d = dir;
    for (const n of ['a', 'b', 'c']) d = await d.getDirectoryHandle(n, { create: true });
    const walk = async (h, prefix = '') => { const out = []; for await (const [n, x] of h.entries()) { out.push(prefix + n + (x.kind === 'directory' ? '/' : '')); if (x.kind === 'directory') out.push(...await walk(x, `${prefix}${n}/`)); } return out.sort(); };
    c.check('nested empty directory a/b/c is enumerable', (await walk(dir)).includes('a/b/c/'));

    // Byte fidelity.
    const samples = {
      'crlf.txt': [0x61, 0x0d, 0x0a, 0x62, 0x0d, 0x0a], 'lone-cr.txt': [0x61, 0x0d, 0x62], 'bom.txt': [0xef, 0xbb, 0xbf, 0x68],
      'zero-bytes.bin': [0, 1, 0, 0xff], 'empty.txt': [], 'latin1.txt': [0xe9, 0xe8, 0xff, 0xfe],
    };
    let exact = true;
    for (const [n, arr] of Object.entries(samples)) { const u = new Uint8Array(arr); await writeBytes(dir, n, u); exact &&= bytesEqual(u, await readBytes(dir, n)); }
    c.check('CRLF, lone CR, BOM, NUL, empty, invalid UTF-8 round-trip byte-exact', exact);

    // Case collision.
    await writeBytes(dir, 'Case.txt', new Uint8Array([1]));
    const lower = await attempt(() => dir.getFileHandle('case.txt', { create: true }));
    const names = await listNames(dir);
    const caseFolds = lower.ok && names.filter((n) => n.toLowerCase() === 'case.txt').length === 1;
    data.caseInsensitiveFilesystem = caseFolds;
    c.note('case-insensitive volume (Case.txt vs case.txt are one entry)', caseFolds);
    c.note('listing after creating Case.txt then case.txt', names.filter((n) => /^case/i.test(n)));

    // Unicode normalisation collision.
    const nfc = 'café.txt', nfd = 'café.txt';
    await writeBytes(dir, nfc, new Uint8Array([1]));
    const second = await attempt(() => dir.getFileHandle(nfd, { create: true }));
    const uni = (await listNames(dir)).filter((n) => n.normalize('NFC') === nfc);
    data.unicodeNormalizationFolds = uni.length === 1;
    c.note('NFC vs NFD names are one entry on this volume', uni.length === 1);
    c.note('names stored (code points)', uni.map((n) => [...n].map((ch) => ch.codePointAt(0).toString(16)).join(' ')));
    c.note('creating NFD sibling', second.ok ? 'ok' : second.error);

    // Names the browser or OS may reject.
    const probes = ['..', '.', 'a/b', 'a\\b', 'CON', 'con.txt', 'NUL', 'aux.log', 'LPT1', 'trailing.', 'trailing ', ' leading', 'colon:name', 'q?mark', 'star*', 'lt<gt>', 'pipe|', 'quote"', 'tab\tname', 'nul\u0000char', 'x'.repeat(255), 'x'.repeat(256), '🙂.txt', '.git', '.GIT'];
    data.nameProbes = {};
    for (const n of probes) {
      const r = await attempt(async () => { await writeBytes(dir, n, new Uint8Array([1])); return 'created'; });
      data.nameProbes[JSON.stringify(n.length > 20 ? n.slice(0, 8) + `…(${n.length})` : n)] = r.ok ? r.value : r.error;
    }
    c.note('name probes (what each platform accepts; informs the portable path policy)', data.nameProbes);

    // Path depth.
    let cur = dir, depth = 0, err = null;
    for (; depth < 200; depth++) {
      const r = await attempt(() => cur.getDirectoryHandle('d'.repeat(10) + depth, { create: true }));
      if (!r.ok) { err = r.error; break; }
      cur = r.value;
    }
    data.maxDepthReached = depth;
    c.note('nested directory depth reached (cap 200 levels, ~13 chars each)', { depth, stoppedBy: err ?? 'cap' });
    return { checks: c.items, status: c.status, data };
  },
};

export const links = {
  id: 'links',
  title: 'Symlinks, junctions, loops (needs fixture generated with --links)',
  needsRoot: true,
  spec: '§5 skip symlinks; browser behaviour must be tested and unsupported behaviour reported',
  options: [{ key: 'removeLinks', label: 'Also remove links via removeEntry (destructive to fixture)', default: false, type: 'checkbox' }],
  async run(ctx) {
    const c = new Checks();
    const data = { entries: {} };
    const base = await ctx.root.getDirectoryHandle('__links__').catch(() => null);
    if (!base) { c.note('fixture', 'No __links__ directory in the selected folder. Generate with --links (see README).'); return { checks: c.items, status: 'skipped' }; }

    // Entry kinds reported for each link. Can the browser distinguish a link from a regular entry at all?
    for await (const [name, h] of base.entries()) {
      const info = { kind: h.kind };
      if (h.kind === 'file') {
        const r = await attempt(async () => (await h.getFile()).size);
        info.read = r.ok ? `ok (${r.value} bytes)` : r.error;
      } else {
        const r = await attempt(async () => { const n = []; for await (const [x] of h.entries()) { n.push(x); if (n.length > 20) break; } return n; });
        info.list = r.ok ? r.value : r.error;
      }
      data.entries[name] = info;
    }
    c.note('how each entry in __links__ appears (kind/readable/listing)', data.entries);
    c.note('dangling-link as enumerated (absent means the browser hides broken links)', data.entries['dangling-link'] ?? 'not listed');

    // Cycle hazard: depth-limited walk must terminate and show repeating names when a loop is followed.
    let maxDepth = 0, visited = 0;
    const walk = async (h, depth) => {
      maxDepth = Math.max(maxDepth, depth); visited++;
      if (depth >= 12 || visited > 5000) return;
      for await (const [, x] of h.entries()) if (x.kind === 'directory') await walk(x, depth + 1);
    };
    await walk(base, 0);
    data.loop = { maxDepth, visited };
    c.note('depth-limited walk (a depth of 12 means a followed loop was cut off by the limit)', data.loop);
    c.note('loop followed?', maxDepth >= 12 ? 'yes: the scanner needs its own cycle/depth guard' : 'no: loop links are not descended');

    if (ctx.opts.removeLinks) {
      const sentinelBefore = await attempt(async () => (await (await (await base.getDirectoryHandle('real-dir')).getFileHandle('sentinel.txt')).getFile()).size);
      const removal = {};
      for (const n of Object.keys(data.entries)) {
        if (n === 'real-dir' || n === 'real-file.txt') continue;
        const r = await attempt(() => base.removeEntry(n));
        removal[n] = r.ok ? 'removed' : r.error;
      }
      const sentinelAfter = await attempt(async () => (await (await (await base.getDirectoryHandle('real-dir')).getFileHandle('sentinel.txt')).getFile()).size);
      data.removal = removal;
      c.note('removeEntry on each link (non-recursive)', removal);
      c.check('link-to-dir removal left the target contents intact', sentinelBefore.ok && sentinelAfter.ok && sentinelBefore.value === sentinelAfter.value, JSON.stringify({ sentinelBefore, sentinelAfter }));
    }
    return { checks: c.items, status: c.status, data };
  },
};
