#!/usr/bin/env node
// Semi-automated real-folder run: launches a visible Chrome/Edge, clicks "Select folder" with a real input event,
// answers the native folder dialog and the permission bubble via tools/pick-folder.ps1 (Windows UI Automation),
// runs the chosen test cards and saves results to harness/results/ through the harness server.
//   node tools/folder-run.mjs --browser chrome --folder C:\fx\1k --scenario 1k [--tests capabilities,hashing,...]
// Requires the harness server (node server.mjs) -- it is started automatically if nothing answers on --url.
// The browser window must stay visible and unobstructed; do not type or click while it runs.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const here = path.dirname(fileURLToPath(import.meta.url));
const { values: a } = parseArgs({ options: {
  browser: { type: 'string', default: 'chrome' }, folder: { type: 'string' }, scenario: { type: 'string', default: '' },
  tests: { type: 'string', default: 'capabilities,hashing,staged-write,entries,links,perf' },
  url: { type: 'string', default: 'http://localhost:8443' }, port: { type: 'string', default: '9335' },
  timeout: { type: 'string', default: '600' }, probe: { type: 'boolean', default: false },
} });
if (!a.folder) { console.error('--folder is required'); process.exit(2); }
const exe = { chrome: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', edge: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' }[a.browser];
const procName = a.browser === 'edge' ? 'msedge' : 'chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const children = [];
const cleanup = () => children.forEach((c) => { try { c.kill(); } catch {} });
process.on('exit', cleanup);

let server;
try { await fetch(a.url); } catch {
  server = spawn(process.execPath, [path.join(here, '..', 'server.mjs')], { stdio: 'ignore' }); children.push(server);
  for (let i = 0; i < 25; i++) { await sleep(200); try { await fetch(a.url); break; } catch {} }
}

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'harness-folder-'));
// A pre-existing browser process with another profile would swallow the launch: use a dedicated profile + debug port.
const browser = spawn(exe, ['--no-first-run', '--no-default-browser-check', `--user-data-dir=${profile}`, `--remote-debugging-port=${a.port}`, '--window-size=1280,900', a.url], { stdio: 'ignore' });
children.push(browser);
let target;
for (let i = 0; i < 60 && !target; i++) { await sleep(250); try { target = (await (await fetch(`http://127.0.0.1:${a.port}/json`)).json()).find((t) => t.type === 'page' && t.url.startsWith(a.url)); } catch {} }
if (!target) { console.error('could not reach browser DevTools'); process.exit(2); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let n = 0; const waiting = new Map();
ws.onmessage = (m) => { const d = JSON.parse(m.data); waiting.get(d.id)?.(d); };
const send = (method, params = {}) => new Promise((res) => { const i = ++n; waiting.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expression) => (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.result?.value;
await send('Page.bringToFront');

async function realClick(selector) {
  const r = await ev(`(() => { const b = document.querySelector(${JSON.stringify(selector)}); b.scrollIntoView({block:'center'}); const r = b.getBoundingClientRect(); return {x: r.x + r.width / 2, y: r.y + r.height / 2}; })()`);
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await send('Input.dispatchMouseEvent', { type, x: r.x, y: r.y, button: 'left', clickCount: 1 });
}

await ev(`(() => { const s = document.querySelector('#scenario'); s.value = ${JSON.stringify(a.scenario)}; s.dispatchEvent(new Event('change')); })()`);
await realClick('#pick');
const pick = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(here, 'pick-folder.ps1'), '-Path', a.folder, '-ProcessName', procName, ...(a.probe ? ['-Probe'] : [])], { stdio: 'inherit' });
const code = await new Promise((r) => pick.on('exit', r));
if (a.probe) process.exit(code);
if (code !== 0) { console.error(`pick-folder.ps1 failed (${code})`); process.exit(code); }

let info = '';
for (let i = 0; i < 40; i++) { info = await ev(`document.querySelector('#root-info').textContent`); if (/permission: granted/.test(info)) break; await sleep(500); }
console.log(`root: ${info}`);
if (!/permission: granted/.test(info)) process.exit(3);

let bad = 0;
for (const id of a.tests.split(',')) {
  await realClick(`#tests .card:has(#res-${id}) button.run`);
  const t0 = Date.now();
  let status = 'running';
  while (Date.now() - t0 < Number(a.timeout) * 1000) {
    await sleep(500);
    status = await ev(`document.querySelector('#res-${id} .badge')?.textContent ?? 'running'`);
    if (status !== 'running…' && status !== 'running') break;
  }
  const res = await ev(`JSON.parse(localStorage.getItem('foldersync-harness-results')).results[${JSON.stringify(id)}]`);
  console.log(`== ${id}: ${res?.status ?? status}${res?.durationMs ? ` (${Math.round(res.durationMs)} ms)` : ''}${res?.error ? '\n' + res.error : ''}`);
  for (const c of res?.checks ?? []) {
    if (c.ok === false) bad++;
    console.log(`   ${c.ok === true ? 'ok  ' : c.ok === false ? 'FAIL' : '..  '} ${c.name}${c.detail ? ' | ' + String(c.detail).slice(0, 200) : ''}`);
  }
  if (!res || res.status === 'error') bad++;
}
await realClick('#save');
await sleep(1000);
console.log(await ev(`document.querySelector('#save-info').textContent`));
ws.close();
process.exit(bad ? 1 : 0);
