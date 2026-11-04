import { Checks, attempt, freshScratch, writeBytes, readBytes, bytesEqual, listNames, patternChunk } from '../lib/util.js';
import { sha256Hex } from '../lib/sha256.js';

const enc = (s) => new TextEncoder().encode(s);
const dec = (b) => new TextDecoder().decode(b);

export default {
  id: 'staged-write',
  title: 'Staged writes, abort, post-write verification, nonrecursive removal',
  needsRoot: true,
  spec: '§7 staged writes / abort / verify; nonrecursive directory removal',
  async run(ctx) {
    const c = new Checks();
    const dir = await freshScratch(ctx.root);
    const data = {};

    // A. Original stays intact while a writable is open; where is the staging artefact?
    await writeBytes(dir, 'doc.txt', enc('ORIGINAL'));
    let fh = await dir.getFileHandle('doc.txt');
    let w = await fh.createWritable();
    await w.write(enc('REPLACEMENT-IN-PROGRESS'));
    data.entriesWhileWriterOpen = await listNames(dir);
    const midRead = dec(await readBytes(dir, 'doc.txt'));
    c.check('original content visible to readers until close()', midRead === 'ORIGINAL', midRead);
    c.note('directory listing while writer is open (staging artefact visible?)', data.entriesWhileWriterOpen);
    await w.close();
    c.check('after close() the replacement is in place', dec(await readBytes(dir, 'doc.txt')) === 'REPLACEMENT-IN-PROGRESS');
    c.check('no stray entries after close()', (await listNames(dir)).join() === 'doc.txt', (await listNames(dir)).join());

    // B. abort() leaves the original and no stray staging file.
    w = await (await dir.getFileHandle('doc.txt')).createWritable();
    await w.write(enc('PARTIAL-THAT-MUST-NOT-LAND'));
    const ab = await attempt(() => w.abort());
    c.check('abort() succeeds', ab.ok, ab.error);
    c.check('original intact after abort()', dec(await readBytes(dir, 'doc.txt')) === 'REPLACEMENT-IN-PROGRESS');
    c.check('no stray entries after abort()', (await listNames(dir)).join() === 'doc.txt', (await listNames(dir)).join());

    // C. A failing write (invalid argument) then abort leaves the original intact.
    w = await (await dir.getFileHandle('doc.txt')).createWritable();
    await w.write(enc('half'));
    const invalid = [{ type: 'bogus' }, { type: 'truncate' }, { type: 'write', position: -1, data: enc('x') }];
    const rejections = [];
    for (const cmd of invalid) { const r = await attempt(() => w.write(cmd)); rejections.push(`${JSON.stringify(cmd.type)}: ${r.ok ? 'accepted' : r.error}`); if (!r.ok) break; }
    c.note('invalid write commands tried until one rejects', rejections);
    await attempt(() => w.abort());
    c.check('original intact after failed write + abort', dec(await readBytes(dir, 'doc.txt')) === 'REPLACEMENT-IN-PROGRESS');
    c.check('no stray entries after failed write + abort', (await listNames(dir)).join() === 'doc.txt', (await listNames(dir)).join());

    // D. Abandoned writer (never closed/aborted), e.g. navigation: observe state.
    const abandoned = await (await dir.getFileHandle('doc.txt')).createWritable();
    await abandoned.write(enc('ABANDONED'));
    data.entriesWithAbandonedWriter = await listNames(dir);
    c.note('listing with an unclosed writer (page-unload analogue; abort afterwards)', data.entriesWithAbandonedWriter);
    await attempt(() => abandoned.abort());

    // E. Post-write verification of a multi-chunk file.
    const big = new Uint8Array(5 << 20);
    for (let i = 0; i < 5; i++) big.set(patternChunk(i), i << 20);
    const h = await (await dir.getFileHandle('big.bin', { create: true })).createWritable();
    for (let off = 0; off < big.length; off += 1 << 20) await h.write(big.subarray(off, off + (1 << 20)));
    await h.close();
    const f = await (await dir.getFileHandle('big.bin')).getFile();
    c.check('post-write size matches', f.size === big.length, f.size);
    c.check('post-write hash matches', sha256Hex(new Uint8Array(await f.arrayBuffer())) === sha256Hex(big));

    // F. Two concurrent writers on one file (an in-browser analogue of a competing writer).
    await writeBytes(dir, 'two.txt', enc('BASE'));
    const hh = await dir.getFileHandle('two.txt');
    const w1 = await attempt(() => hh.createWritable());
    const w2 = await attempt(() => hh.createWritable());
    data.secondWritableAllowed = w2.ok;
    c.note('second createWritable() while first is open', w2.ok ? 'allowed' : w2.error);
    if (w1.ok && w2.ok) {
      await w1.value.write(enc('FIRST')); await w2.value.write(enc('SECOND'));
      const c1 = await attempt(() => w1.value.close()); const c2 = await attempt(() => w2.value.close());
      c.note('close results', { first: c1.ok ? 'ok' : c1.error, second: c2.ok ? 'ok' : c2.error });
      c.note('surviving content (last close wins?)', dec(await readBytes(dir, 'two.txt')));
    } else { for (const x of [w1, w2]) if (x.ok) await attempt(() => x.value.abort()); }

    // G. Nonrecursive removal only succeeds on empty directories.
    const parent = await dir.getDirectoryHandle('p', { create: true });
    await writeBytes(parent, 'child.txt', enc('x'));
    const r1 = await attempt(() => dir.removeEntry('p'));
    c.check('removeEntry(non-empty dir) without recursive fails', !r1.ok, r1.ok ? 'REMOVED' : r1.error);
    c.check('non-empty directory and child survive the rejected removal', (await listNames(parent)).join() === 'child.txt');
    await parent.removeEntry('child.txt');
    const nested = await parent.getDirectoryHandle('empty-sub', { create: true });
    void nested;
    const r2 = await attempt(() => dir.removeEntry('p'));
    c.check('removeEntry(dir containing only an empty subdir) without recursive fails', !r2.ok, r2.ok ? 'REMOVED' : r2.error);
    await parent.removeEntry('empty-sub');
    const r3 = await attempt(() => dir.removeEntry('p'));
    c.check('removeEntry(empty dir) succeeds', r3.ok, r3.error);

    // H. Truncation semantics: default createWritable() starts empty; keepExistingData preserves.
    await writeBytes(dir, 'trunc.txt', enc('0123456789'));
    let t = await (await dir.getFileHandle('trunc.txt')).createWritable({ keepExistingData: true });
    await t.write({ type: 'write', position: 2, data: enc('AB') });
    await t.close();
    c.check('keepExistingData:true overwrites in place', dec(await readBytes(dir, 'trunc.txt')) === '01AB456789');
    await writeBytes(dir, 'trunc.txt', enc('xy'));
    c.check('default createWritable truncates', dec(await readBytes(dir, 'trunc.txt')) === 'xy');
    return { checks: c.items, status: c.status, data };
  },
};
