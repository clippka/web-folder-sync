import { Checks, freshScratch, writeBytes, readBytes, sleep, patternChunk } from '../lib/util.js';
import { sha256Hex } from '../lib/sha256.js';

const enc = (s) => new TextEncoder().encode(s);
const dec = (b) => new TextDecoder().decode(b);

async function snapshot(dir, name) {
  const f = await (await dir.getFileHandle(name)).getFile();
  return { size: f.size, lastModified: f.lastModified, hash: sha256Hex(new Uint8Array(await f.arrayBuffer())) };
}
async function readAck(dir, timeoutMs) {
  const t0 = performance.now();
  while (performance.now() - t0 < timeoutMs) {
    try { return JSON.parse(dec(await readBytes(dir, 'race-injector.ack'))); } catch { await sleep(10); }
  }
  return null;
}
async function mark(dir, label) {
  try { await dir.removeEntry('race-injector.ack'); } catch {}
  await writeBytes(dir, 'race.marker', enc(`${label}@${performance.now()}`));
}

export default {
  id: 'race',
  title: 'External-editor race: before replacement, between recheck and write, during write',
  needsRoot: true,
  spec: '§7 recheck before mutation, remaining race window, acceptance 11',
  hint: 'Start the injector in a terminal first:  node tools/race-injector.mjs <the folder you selected>  [append|truncate-write|replace]. Run once per injector mode and record the mode in the scenario label.',
  options: [{ key: 'ackWaitMs', label: 'Wait for injector (ms)', default: 3000 }],
  async run(ctx) {
    const c = new Checks();
    const data = { injectorMode: null };
    const dir = await freshScratch(ctx.root);
    const wait = ctx.opts.ackWaitMs;
    const target = 'race-target.txt';

    // Probe the injector.
    await writeBytes(dir, target, enc('PROBE\n'));
    await mark(dir, 'probe');
    const probe = await readAck(dir, wait);
    if (!probe) { c.note('injector', 'not detected: start tools/race-injector.mjs for the selected folder and run again'); return { checks: c.items, status: 'skipped' }; }
    data.injectorMode = probe.mode;
    c.note('injector detected', probe);

    // Phase A: external write after the plan was made but before our replacement starts.
    await writeBytes(dir, target, enc('ORIGINAL\n'));
    const planned = await snapshot(dir, target);
    await mark(dir, 'A-before');
    const ackA = await readAck(dir, wait);
    await sleep(30);
    const now = await snapshot(dir, target);
    data.phaseA = { injectorAck: ackA, planned, now };
    c.check('recheck immediately before mutation detects the external edit (hash differs)', now.hash !== planned.hash, `${planned.hash.slice(0, 8)} -> ${now.hash.slice(0, 8)}`);
    c.note('does size or lastModified alone reveal it? (cache optimisations must not rely on these)', { sizeChanged: now.size !== planned.size, lastModifiedChanged: now.lastModified !== planned.lastModified });

    // Phase B: external write between the final recheck and createWritable()/close (the unprotected window).
    await writeBytes(dir, target, enc('ORIGINAL\n'));
    const planB = await snapshot(dir, target);
    const recheck = await snapshot(dir, target);
    c.check('final recheck passes while nothing changed', recheck.hash === planB.hash);
    await mark(dir, 'B-after-recheck');
    const ackB = await readAck(dir, wait);
    const intendedB = enc('REPLACEMENT-B\n');
    const wB = await (await dir.getFileHandle(target)).createWritable();
    await wB.write(intendedB);
    await wB.close();
    const finalB = dec(await readBytes(dir, target));
    data.phaseB = { injectorAck: ackB, finalContent: finalB.slice(0, 120) };
    c.note('Phase B outcome (external edit between recheck and write)', finalB.includes('EXTERNAL') ? 'external edit SURVIVED (merged/appended)' : 'external edit SILENTLY LOST (replacement overwrote it)');

    // Phase C: external write while our writable is open and half the data is written.
    await writeBytes(dir, target, enc('ORIGINAL\n'));
    const wC = await (await dir.getFileHandle(target)).createWritable();
    for (let i = 0; i < 2; i++) await wC.write(patternChunk(i));
    await mark(dir, 'C-during');
    const ackC = await readAck(dir, wait);
    for (let i = 2; i < 4; i++) await wC.write(patternChunk(i));
    const closeC = await wC.close().then(() => 'ok', (e) => `${e.name}: ${e.message}`);
    const f = await (await dir.getFileHandle(target)).getFile();
    const finalC = new Uint8Array(await f.arrayBuffer());
    const txt = dec(finalC.subarray(0, Math.min(finalC.length, 64)));
    data.phaseC = { injectorAck: ackC, close: closeC, finalSize: f.size, startsWithExternal: txt.includes('EXTERNAL') };
    c.note('Phase C injector result (an error here, e.g. sharing violation, means the OS protected the file)', ackC);
    c.note('Phase C writable close()', closeC);
    c.note('Phase C outcome (external edit during replacement)', f.size === (4 << 20) && !txt.includes('EXTERNAL') ? 'external edit SILENTLY LOST' : `final size ${f.size}; external text in first 64 bytes: ${txt.includes('EXTERNAL')}`);

    // Window width: how long a typical replacement is exposed (recheck -> close).
    const widths = {};
    for (const [label, size] of [['4 KiB', 4096], ['1 MiB', 1 << 20], ['10 MiB', 10 << 20]]) {
      const samples = [];
      for (let i = 0; i < (size > 1 << 20 ? 5 : 20); i++) {
        const t0 = performance.now();
        const fh = await dir.getFileHandle('window.bin', { create: true });
        await (await fh.getFile()).arrayBuffer();
        const w = await fh.createWritable();
        for (let off = 0; off < size; off += 1 << 20) await w.write(patternChunk(i, 1 << 20).subarray(0, Math.min(1 << 20, size - off)));
        await w.close();
        samples.push(performance.now() - t0);
      }
      samples.sort((a, b) => a - b);
      widths[label] = { p50: +samples[Math.floor(samples.length / 2)].toFixed(1), max: +samples[samples.length - 1].toFixed(1) };
    }
    data.windowMs = widths;
    c.note('exposure window recheck→close, ms (this is the race window users must be warned about)', widths);
    return { checks: c.items, status: c.status, data };
  },
};
