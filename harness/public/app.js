import { collectEnv, osKey } from './lib/env.js';
import { runHolder } from './tests/locks.js';
import { mountLifecycle } from './lifecycle.js';
import { MANUAL } from './manual.js';
import capabilities from './tests/capabilities.js';
import { handlePersistence, identityOther, handleReload } from './tests/handles.js';
import hashing from './tests/hashing.js';
import stagedWrite from './tests/staged-write.js';
import { entries, links } from './tests/entries.js';
import locks from './tests/locks.js';
import race from './tests/race.js';
import perf from './tests/perf.js';

const params = new URLSearchParams(location.search);
if (params.get('role') === 'holder') { runHolder(); throw new Error('holder mode: no UI'); }

const TESTS = [capabilities, handlePersistence, identityOther, handleReload, locks, hashing, stagedWrite, entries, links, race, perf];
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const STORE = 'foldersync-harness-results';

let env = await collectEnv();
let root = null, rootKind = null;
const state = (() => { try { return JSON.parse(localStorage.getItem(STORE)) ?? {}; } catch { return {}; } })();
state.results ??= {}; state.history ??= []; state.manual ??= {};
const persist = () => { try { localStorage.setItem(STORE, JSON.stringify(state)); } catch {} };
const scenario = () => $('#scenario').value.trim();

$('#env').textContent = `${env.browser} ${env.browserVersion ?? '?'} on ${env.platform} ${env.platformVersion ?? ''} | ${env.origin} | secure=${env.secureContext} | cores=${env.hardwareConcurrency} | heap sampling=${env.hasPerformanceMemory}`;
if (!env.secureContext) $('#env').textContent += '\nWARNING: not a secure context: File System Access will not work. Use https:// or localhost.';

function record(id, result) {
  const full = { ...result, id, scenario: scenario(), at: new Date().toISOString(), rootKind };
  state.results[id] = full;
  state.history.push(full);
  persist();
  renderResult(id);
}

function renderResult(id) {
  const el = document.getElementById(`res-${id}`);
  const r = state.results[id];
  if (!el || !r) return;
  const checks = (r.checks ?? []).map((c) => `<li><span class="${c.ok === true ? 'pass' : c.ok === false ? 'fail' : 'info'}">${c.ok === true ? '✔' : c.ok === false ? '✖' : '•'}</span> ${esc(c.name)}${c.detail ? ` <span class="d">— ${esc(c.detail)}</span>` : ''}</li>`).join('');
  el.innerHTML = `<span class="badge ${r.status}">${r.status}</span> <span class="small">${esc(r.at)}${r.scenario ? ' · ' + esc(r.scenario) : ''}${r.durationMs ? ` · ${Math.round(r.durationMs)} ms` : ''}${r.rootKind === 'opfs' ? ' · OPFS smoke (not valid evidence)' : ''}</span>
    ${r.error ? `<pre>${esc(r.error)}</pre>` : ''}<ul class="checks">${checks}</ul>${r.data ? `<details><summary>raw data</summary><pre>${esc(JSON.stringify(r.data, null, 1))}</pre></details>` : ''}`;
}

async function runTest(t, card) {
  const opts = {};
  for (const o of t.options ?? []) {
    const input = card.querySelector(`[data-opt="${o.key}"]`);
    opts[o.key] = o.type === 'checkbox' ? input.checked : o.type === 'text' ? input.value : Number(input.value);
  }
  const res = document.getElementById(`res-${t.id}`);
  if (t.needsRoot && !root) { res.innerHTML = '<span class="badge error">error</span> Select a folder first.'; return; }
  const btn = card.querySelector('button.run');
  btn.disabled = true;
  res.innerHTML = '<span class="badge running">running…</span>';
  const t0 = performance.now();
  try {
    const out = await t.run({ root, rootKind, opts, env, log: console.log });
    record(t.id, { ...out, durationMs: performance.now() - t0 });
  } catch (e) {
    record(t.id, { status: 'error', error: `${e.name}: ${e.message}\n${e.stack ?? ''}`, durationMs: performance.now() - t0, checks: [] });
  } finally { btn.disabled = false; }
}

function renderTests() {
  const host = $('#tests');
  for (const t of TESTS) {
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = `<h3>${esc(t.title)}</h3><div class="small">${esc(t.spec ?? '')}</div>${t.hint ? `<div class="small">${esc(t.hint)}</div>` : ''}
      <div class="row">${(t.options ?? []).map((o) => `<label>${esc(o.label)} <input data-opt="${o.key}" ${o.type === 'checkbox' ? `type="checkbox" ${o.default ? 'checked' : ''}` : `value="${esc(o.default)}" size="${o.type === 'text' ? 28 : 6}"`}></label>`).join('')}
      <button class="run">Run</button></div><div id="res-${t.id}"></div>`;
    card.querySelector('button.run').onclick = () => runTest(t, card);
    host.append(card);
    renderResult(t.id);
  }
}

function renderManual() {
  const host = $('#manual');
  for (const m of MANUAL) {
    const cur = state.manual[m.id] ?? { result: '', notes: '' };
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = `<div>${esc(m.text)}</div><div class="row"><select>${['', 'pass', 'fail', 'n.a.'].map((v) => `<option ${cur.result === v ? 'selected' : ''}>${v}</option>`).join('')}</select><span class="small">${esc(m.id)}</span></div><textarea placeholder="Observed behaviour, error names, versions…">${esc(cur.notes)}</textarea>`;
    const save = () => { state.manual[m.id] = { result: card.querySelector('select').value, notes: card.querySelector('textarea').value, scenario: scenario(), at: new Date().toISOString() }; persist(); };
    card.querySelector('select').onchange = save;
    card.querySelector('textarea').onchange = save;
    host.append(card);
  }
}

$('#pick').onclick = async () => {
  try {
    root = await window.showDirectoryPicker({ id: 'harness-root', mode: 'readwrite' });
    rootKind = 'picked';
    const perm = await root.requestPermission({ mode: 'readwrite' });
    $('#root-info').textContent = `${root.name} (permission: ${perm})`;
  } catch (e) { $('#root-info').textContent = `selection failed: ${e.name}: ${e.message}`; }
};

function resultFile() {
  return { harness: 1, env: { ...env, timestamp: new Date().toISOString() }, scenario: scenario(), results: state.results, history: state.history, manual: state.manual };
}
$('#download').onclick = () => {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([JSON.stringify(resultFile(), null, 1)], { type: 'application/json' }));
  a.download = fileName();
  a.click();
  URL.revokeObjectURL(a.href);
};
const fileName = () => `${osKey(env)}-${env.browser}-${(env.browserVersion ?? 'x').split('.')[0]}${scenario() ? '-' + scenario().replace(/[^\w.-]+/g, '_') : ''}.json`;
$('#save').onclick = async () => {
  const r = await fetch(`/results/${fileName()}`, { method: 'PUT', body: JSON.stringify(resultFile()) }).catch((e) => ({ ok: false, status: e.message }));
  $('#save-info').textContent = r.ok ? `saved harness/results/${fileName()}` : `save failed (${r.status})`;
};
$('#reset').onclick = () => { if (confirm('Clear all stored results in this browser?')) { try { localStorage.removeItem(STORE); } catch {} location.reload(); } };

renderTests();
renderManual();
mountLifecycle($('#lifecycle'), (id, r) => record(id, r));

// Headless smoke mode: runs the folder-independent tests against the origin-private file system.
// Not valid evidence: OPFS is not a user-visible disk folder. It only proves the harness code runs.
async function seedFixture(dir) {
  // ~600 included files plus ~400 files under pruned directories, hashed on the main thread for the manifest.
  const { sha256Hex } = await import('./lib/sha256.js');
  const files = [];
  const put = async (path, bytes) => {
    let d = dir; const parts = path.split('/');
    for (const p of parts.slice(0, -1)) d = await d.getDirectoryHandle(p, { create: true });
    const w = await (await d.getFileHandle(parts.at(-1), { create: true })).createWritable(); await w.write(bytes); await w.close();
  };
  for (let i = 0; i < 600; i++) {
    const bytes = new TextEncoder().encode(`file ${i}\r\n`.repeat(1 + (i % 50)) + 'x'.repeat(i * 37 % 3000));
    const path = `src/p${i % 12}/m${i % 5}/F${i}.kt`;
    await put(path, bytes); files.push({ path, size: bytes.length, sha256: sha256Hex(bytes) });
  }
  for (let i = 0; i < 400; i++) await put(`${['node_modules', 'build', 'dist'][i % 3]}/pkg${i % 20}/f${i}.js`, new Uint8Array(10));
  await (await dir.getDirectoryHandle('empty', { create: true })).getDirectoryHandle('nested', { create: true });
  const manifest = { files, boundary: [], counts: { includedFiles: files.length, excludedFiles: 400 } };
  await put('.fixture-manifest.json', new TextEncoder().encode(JSON.stringify(manifest)));
}

if (params.get('autorun') === 'opfs') {
  rootKind = 'opfs';
  root = await navigator.storage.getDirectory();
  const out = {};
  const perfRoot = await root.getDirectoryHandle('perf-fixture', { create: true });
  await seedFixture(perfRoot);
  for (const t of [capabilities, handlePersistence, handleReload, hashing, stagedWrite, entries, links, perf]) {
    if (t === perf) { root = perfRoot; }
    const opts = Object.fromEntries((t.options ?? []).map((o) => [o.key, o.key === 'bigMiB' ? 4 : o.key === 'cancelAfterMs' ? 12 : o.default]));
    const t0 = performance.now();
    try { out[t.id] = { ...(await t.run({ root, rootKind, opts, env, log() {} })), ms: Math.round(performance.now() - t0) }; }
    catch (e) { out[t.id] = { status: 'error', error: `${e.name}: ${e.message}\n${e.stack}` }; }
  }
  const pre = document.createElement('pre');
  pre.id = 'autorun-result';
  pre.textContent = JSON.stringify(out);
  document.body.append(pre);
  document.title = 'autorun-done';
}
