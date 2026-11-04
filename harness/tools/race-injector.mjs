#!/usr/bin/env node
// External-editor race injector.
//   node tools/race-injector.mjs <selected-folder>
// Watches <folder>/__harness_scratch__/race.marker, which the harness' race test rewrites at specific phases.
// When the marker changes, it immediately writes to race-target.txt from outside the browser (as a text editor
// would) and records the outcome in race-injector.ack for the harness to pick up. Polling is used on purpose:
// fs.watch is unreliable across the supported platforms.
import fs from 'node:fs';
import path from 'node:path';

const folder = process.argv[2];
if (!folder) { console.error('usage: race-injector.mjs <selected-folder>'); process.exit(2); }
const scratch = path.join(path.resolve(folder), '__harness_scratch__');
const marker = path.join(scratch, 'race.marker');
const target = path.join(scratch, 'race-target.txt');
const ack = path.join(scratch, 'race-injector.ack');
const mode = process.argv[3] ?? 'append'; // append | truncate-write | replace

let last = '';
console.log(`Watching ${marker} (mode ${mode}). Ctrl+C to stop.`);
setInterval(() => {
  let m;
  try { m = fs.readFileSync(marker, 'utf8'); } catch { return; }
  if (!m || m === last) return;
  last = m;
  const t0 = process.hrtime.bigint();
  const rec = { marker: m, mode, at: new Date().toISOString() };
  try {
    const text = `\nEXTERNAL-EDIT(${m.trim()})@${Date.now()}\n`;
    if (mode === 'append') fs.appendFileSync(target, text);
    else if (mode === 'truncate-write') fs.writeFileSync(target, `EXTERNAL-FULL(${m.trim()})${text}`);
    else if (mode === 'replace') { fs.writeFileSync(target + '.tmp', `EXTERNAL-REPLACED(${m.trim()})${text}`); fs.renameSync(target + '.tmp', target); }
    rec.ok = true;
  } catch (e) {
    rec.ok = false; rec.error = `${e.code ?? e.name}: ${e.message}`;
  }
  rec.durationMs = Number(process.hrtime.bigint() - t0) / 1e6;
  try { fs.writeFileSync(ack, JSON.stringify(rec)); } catch {}
  console.log(JSON.stringify(rec));
}, 3);
