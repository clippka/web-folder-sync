#!/usr/bin/env node
// Headless smoke run of the harness' folder-independent tests against OPFS (origin-private file system).
//   CHROME="/path/to/chrome" node tools/smoke.mjs [--url http://localhost:8443] [--timeout 180]
// This only proves the harness code executes; OPFS is not a user-visible folder, so results are NOT feasibility evidence.
// Requires the harness server to be running (node server.mjs). Uses Node's built-in WebSocket (Node 22+).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { url: { type: 'string', default: 'http://localhost:8443' }, timeout: { type: 'string', default: '180' }, port: { type: 'string', default: '9333' } } });
const chrome = process.env.CHROME ?? {
  darwin: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  linux: 'google-chrome',
  win32: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
}[process.platform];
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'harness-smoke-'));
const proc = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', `--user-data-dir=${profile}`, `--remote-debugging-port=${values.port}`, `${values.url}/?autorun=opfs`], { stdio: 'ignore' });
const cleanup = () => { proc.kill(); try { fs.rmSync(profile, { recursive: true, force: true }); } catch {} };
process.on('exit', cleanup);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try { target = (await (await fetch(`http://127.0.0.1:${values.port}/json`)).json()).find((t) => t.type === 'page'); } catch {}
}
if (!target) { console.error('could not reach Chrome DevTools'); process.exit(2); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0; const waiting = new Map();
ws.onmessage = (m) => { const d = JSON.parse(m.data); waiting.get(d.id)?.(d); };
const evaluate = (expression) => new Promise((res) => { const i = ++id; waiting.set(i, res); ws.send(JSON.stringify({ id: i, method: 'Runtime.evaluate', params: { expression, returnByValue: true } })); });

const deadline = Date.now() + Number(values.timeout) * 1000;
let raw;
while (Date.now() < deadline) {
  const r = await evaluate("document.title === 'autorun-done' ? document.getElementById('autorun-result').textContent : null");
  if (r.result?.result?.value) { raw = r.result.result.value; break; }
  await sleep(500);
}
if (!raw) { console.error('timed out waiting for autorun'); process.exit(1); }
const out = JSON.parse(raw);
let bad = 0;
for (const [k, v] of Object.entries(out)) {
  console.log(`== ${k}: ${v.status}${v.ms ? ` (${v.ms} ms)` : ''}${v.error ? '\n' + v.error : ''}`);
  for (const c of v.checks ?? []) {
    const mark = c.ok === true ? 'ok ' : c.ok === false ? 'FAIL' : '..  ';
    if (c.ok === false) bad++;
    console.log(`   ${mark} ${c.name}${c.detail ? ' | ' + c.detail.slice(0, 220) : ''}`);
  }
  if (v.status === 'error') bad++;
}
process.exit(bad ? 1 : 0);
