#!/usr/bin/env node
// End-to-end check of the real bundle: a real server (the built jar) and two isolated headless Chrome profiles that
// act as two machines. Each "local folder" is an OPFS directory handed out by a stand-in for showDirectoryPicker (a
// real picker cannot be automated). This proves the coordinator, hash worker, CSP, HTMX navigation and the protocol
// work together; it is NOT browser-feasibility evidence for user-selected folders (that is Step 1's harness).
//
//   gradlew bootJar && node e2e/two-browsers.mjs
//   CHROME="/path/to/chrome" JAVA=java PORT=18080 node e2e/two-browsers.mjs
import { spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const jar = fs.readdirSync(path.join(root, "build/libs")).find((f) => /^foldersync-.*SNAPSHOT\.jar$/.test(f));
if (!jar) { console.error("build the jar first: gradlew bootJar"); process.exit(2); }
const PORT = Number(process.env.PORT ?? 18080);
const BASE = `http://localhost:${PORT}`;
const CHROME = process.env.CHROME ?? { win32: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", darwin: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", linux: "google-chrome" }[process.platform];
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "foldersync-e2e-"));
const secret = path.join(tmp, "admin.secret");
fs.writeFileSync(secret, "e2e-admin-password-1");

const procs = [];
const cleanup = () => { for (const p of procs) { try { p.kill(); } catch {} } try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {} };
process.on("exit", cleanup);
process.on("SIGINT", () => process.exit(130));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (name, ok, detail = "") => { console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? ` | ${detail}` : ""}`); if (!ok) failures++; };

// ---- server ----
const server = spawn(process.env.JAVA ?? "java", ["-jar", path.join(root, "build/libs", jar), `--server.port=${PORT}`, `--foldersync.data-dir=${path.join(tmp, "data")}`,
  "--foldersync.admin.username=admin", `--foldersync.admin.password-file=${secret}`, "--server.servlet.session.cookie.secure=false"], { stdio: ["ignore", "pipe", "pipe"] });
procs.push(server);
let serverLog = "";
server.stdout.on("data", (d) => { serverLog += d; });
server.stderr.on("data", (d) => { serverLog += d; });
for (let i = 0; ; i++) {
  if (i > 120) { console.error("server did not start\n" + serverLog.slice(-2000)); process.exit(2); }
  try { if ((await fetch(`${BASE}/actuator/health`)).ok) break; } catch {}
  await sleep(500);
}

// ---- browsers over the DevTools protocol ----
const PICKER_SHIM = `(() => {
  for (const P of [FileSystemDirectoryHandle.prototype]) {
    if (!P.queryPermission) P.queryPermission = async () => 'granted';
    if (!P.requestPermission) P.requestPermission = async () => 'granted';
  }
  window.showDirectoryPicker = async () => (await navigator.storage.getDirectory()).getDirectoryHandle('picked', { create: true });
})();`;

async function launch(name, port) {
  const profile = path.join(tmp, "profile-" + name);
  const proc = spawn(CHROME, ["--headless=new", "--disable-gpu", "--no-first-run", `--user-data-dir=${profile}`, `--remote-debugging-port=${port}`, "about:blank"], { stdio: "ignore" });
  procs.push(proc);
  let target;
  for (let i = 0; i < 60 && !target; i++) {
    await sleep(250);
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === "page"); } catch {}
  }
  if (!target) throw new Error("no DevTools for " + name);
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  let id = 0; const waiting = new Map(); const events = [];
  ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id) waiting.get(d.id)?.(d); else events.push(d); };
  const send = (method, params = {}) => new Promise((res) => { const i = ++id; waiting.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
  await send("Page.enable"); await send("Runtime.enable"); await send("Network.enable");
  await send("Page.addScriptToEvaluateOnNewDocument", { source: PICKER_SHIM });
  const b = {
    name, events,
    async eval(expression) {
      const r = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
      if (r.result?.exceptionDetails) throw new Error(`[${name}] ${r.result.exceptionDetails.exception?.description ?? r.result.exceptionDetails.text}`);
      return r.result?.result?.value;
    },
    async goto(url) { await send("Page.navigate", { url }); await sleep(300); await b.until(() => b.eval("document.readyState"), (v) => v === "complete", 20000); },
    async until(probe, ok, ms = 30000) {
      const end = Date.now() + ms; let v;
      while (Date.now() < end) { v = await probe(); if (ok(v)) return v; await sleep(150); }
      throw new Error(`[${name}] timed out; last value: ${JSON.stringify(v)}`);
    },
    requests: () => events.filter((e) => e.method === "Network.requestWillBeSent").map((e) => `${e.params.request.method} ${new URL(e.params.request.url).pathname}`),
    consoleErrors: () => events.filter((e) => e.method === "Runtime.exceptionThrown" || (e.method === "Runtime.consoleAPICalled" && e.params.type === "error")),
  };
  return b;
}

async function login(b, user, pass) {
  await b.goto(`${BASE}/login`);
  await b.eval(`document.querySelector('[name=username]').value=${JSON.stringify(user)};document.querySelector('[name=password]').value=${JSON.stringify(pass)};document.querySelector('form').submit();0`);
  await b.until(() => b.eval("location.pathname"), (p) => p === "/", 15000);
}

const panelPhase = (b) => b.eval(`document.querySelector('[data-field=phase]')?.textContent ?? ''`);
const running = (b) => b.eval(`!document.querySelector('[data-action=cancel]')?.hidden`);
async function click(b, action) { await b.eval(`document.querySelector('[data-action=${action}]').click();0`); }
async function runAndWait(b, action) {
  await click(b, action);
  await b.until(() => running(b), (v) => v === true, 10000).catch(() => undefined); // may finish before first poll
  await b.until(() => running(b), (v) => v === false, 120000);
  return panelPhase(b);
}

const WRITE_TREE = `async (tree) => {
  const root = await navigator.storage.getDirectory();
  const picked = await root.getDirectoryHandle('picked', { create: true });
  for (const [p, content] of Object.entries(tree)) {
    const parts = p.replace(/[/]$/, '').split('/'); const last = parts.pop(); let d = picked;
    for (const s of parts) d = await d.getDirectoryHandle(s, { create: true });
    if (content === null) { await d.getDirectoryHandle(last, { create: true }); continue; }
    const f = await d.getFileHandle(last, { create: true }); const w = await f.createWritable();
    await w.write(Uint8Array.from(atob(content), (c) => c.charCodeAt(0))); await w.close();
  }
}`;
const READ_TREE = `(async () => {
  const root = await navigator.storage.getDirectory(); const picked = await root.getDirectoryHandle('picked', { create: true });
  const out = {};
  const hex = async (f) => [...new Uint8Array(await crypto.subtle.digest('SHA-256', await f.arrayBuffer()))].map((b) => b.toString(16).padStart(2, '0')).join('');
  const walk = async (dir, prefix) => { for await (const [n, h] of dir.entries()) { const p = prefix + n; if (h.kind === 'directory') { out[p + '/'] = null; await walk(h, p + '/'); } else out[p] = await hex(await h.getFile()); } };
  await walk(picked, ''); return out; })()`;
const b64 = (buf) => Buffer.from(buf).toString("base64");
const sha = (buf) => createHash("sha256").update(buf).digest("hex");

try {
  const A = await launch("A", 9444);
  const B = await launch("B", 9445);
  await login(A, "admin", "e2e-admin-password-1");
  await login(B, "admin", "e2e-admin-password-1");

  // ---- A: create a space through the page, populate a local folder ----
  await A.goto(`${BASE}/spaces`);
  await A.eval(`document.querySelector('[name=name]').value='E2E space';document.querySelector('form[action$="/spaces"]').submit();0`);
  const spaceUrl = await A.until(() => A.eval("location.pathname"), (p) => /^\/spaces\/[^/]+$/.test(p), 15000);
  check("space page renders with coordinator and no console errors", (await A.eval("!!document.getElementById('space-panel')")) && A.consoleErrors().length === 0, JSON.stringify(A.consoleErrors()[0] ?? ""));
  check("unsupported notice hidden where the API exists", (await A.eval("document.getElementById('unsupported').hidden")) === true);

  const crlf = Buffer.from("line one\r\nline two\r\nä ö ü\r\n");
  const bin = randomBytes(20000);
  const tree = {
    "a.txt": b64(crlf), "bin/data.bin": b64(bin), "empty/nested/": null,
    ".gitignore": b64("node_modules/\n*.log\n"), "node_modules/dep/index.js": b64("module.exports=1"), "debug.log": b64("noise"),
    ".git/config": b64("[core]"),
  };
  await A.eval(`(${WRITE_TREE})(${JSON.stringify(tree)})`);
  const requestsBeforeAction = A.requests().filter((r) => r.startsWith("POST /api/")).length;

  await click(A, "connect");
  await A.until(() => A.eval("document.querySelector('[data-field=binding]').textContent"), (t) => t.startsWith("Connected"), 15000);
  check("connecting alone made no plan, upload or commit", A.requests().filter((r) => /plans|uploads|commit/.test(r)).length === 0);
  check("connect only enrolls (devices + bindings)", A.requests().filter((r) => r.startsWith("POST /api/")).length - requestsBeforeAction === 2);

  const preview = await runAndWait(A, "scan");
  check("scan is a preview", preview === "Scan complete", preview);
  const afterScan = await A.eval(`fetch('/api/spaces/${spaceUrl.split("/")[2]}/manifest').then(r => r.json())`);
  check("scan changed nothing on the server", afterScan.entries.length === 0);
  check("scan uploaded nothing", A.requests().filter((r) => /uploads|commit/.test(r)).length === 0);

  const synced = await runAndWait(A, "sync");
  check("A first sync finished", ["Finished", "Up to date"].includes(synced), synced);
  const spaceId = spaceUrl.split("/")[2];
  const manifest = (await A.eval(`fetch('/api/spaces/${spaceId}/manifest').then(r => r.json())`)).entries;
  const paths = manifest.map((e) => e.path).sort();
  check("server holds files, folders and the nested empty directory", ["a.txt", "bin", "bin/data.bin", "empty", "empty/nested", ".gitignore"].every((p) => paths.includes(p)), paths.join(","));
  check("ignored paths never reached the server", !paths.some((p) => p.startsWith("node_modules") || p.endsWith(".log") || p.startsWith(".git/") || p === ".git"), paths.join(","));
  check("server digest matches local bytes", manifest.find((e) => e.path === "a.txt")?.sha256 === sha(crlf));

  // ---- navigation does not start or duplicate work ----
  const beforeNav = A.requests().length;
  await A.goto(`${BASE}/spaces`); await A.goto(`${BASE}${spaceUrl}`);
  await sleep(1500);
  check("reopening the page started no sync", A.requests().slice(beforeNav).filter((r) => /plans|uploads|commit/.test(r)).length === 0 && (await panelPhase(A)) === "");

  // ---- B: second machine joins an empty folder ----
  await B.goto(`${BASE}${spaceUrl}`);
  await click(B, "connect");
  await B.until(() => B.eval("document.querySelector('[data-field=binding]').textContent"), (t) => t.startsWith("Connected"), 15000);
  const bFirst = await runAndWait(B, "sync");
  check("B first sync finished", ["Finished", "Up to date"].includes(bFirst), bFirst);
  const bTree = await B.eval(READ_TREE);
  check("B received exact bytes", bTree["a.txt"] === sha(crlf) && bTree["bin/data.bin"] === sha(bin));
  check("B received nested empty directory", "empty/nested/" in bTree);
  check("B did not receive ignored paths", !Object.keys(bTree).some((p) => p.startsWith("node_modules") || p.endsWith(".log") || p.startsWith(".git/")), Object.keys(bTree).join(","));

  // ---- B edits, A picks it up using stored baselines; B-created directory travels back ----
  const edited = Buffer.from("edited on B\r\n");
  await B.eval(`(${WRITE_TREE})(${JSON.stringify({ "a.txt": b64(edited), "fromB/deep/": null })})`);
  const bSecond = await runAndWait(B, "sync");
  check("B second sync finished", ["Finished", "Up to date"].includes(bSecond), bSecond);
  const aSecond = await runAndWait(A, "sync");
  check("A second sync finished", ["Finished", "Up to date"].includes(aSecond), aSecond);
  const aTree = await A.eval(READ_TREE);
  check("A received B's edit byte-exact", aTree["a.txt"] === sha(edited));
  check("A received B's new nested directory", "fromB/deep/" in aTree);
  check("A kept its ignored files", "node_modules/dep/index.js" in aTree && "debug.log" in aTree);

  const mark = A.requests().length;
  const noChange = await runAndWait(A, "sync");
  check("a no-change run reports up to date", noChange === "Up to date", noChange);
  check("no-change run transferred no file content", A.requests().slice(mark).filter((r) => /uploads|blobs/.test(r)).length === 0, A.requests().slice(mark).join(" ; "));

  // ---- confirmed deletion (Step 8): proposed first, applied only after an explicit confirmation ----
  const deletionUi = (b) => b.eval(`document.querySelector('.deletions') ? { text: document.querySelector('.deletions').textContent, button: !!document.querySelector('[data-action=confirm-deletions]') } : null`);
  const serverEntry = async (p) => (await A.eval(`fetch('/api/spaces/${spaceId}/manifest').then(r => r.json())`)).entries.find((e) => e.path === p);
  await A.eval(`(async () => { const r = await navigator.storage.getDirectory(); const p = await r.getDirectoryHandle('picked'); const d = await p.getDirectoryHandle('bin'); await d.removeEntry('data.bin'); })()`);
  await runAndWait(A, "sync");
  const proposed = await deletionUi(A);
  check("A's local deletion is only proposed, with a confirm button", proposed?.button === true && /bin\/data\.bin/.test(proposed.text), JSON.stringify(proposed));
  check("the server still holds the file before confirmation", (await serverEntry("bin/data.bin"))?.deleted === false);
  await A.eval(`window.confirm = () => true; 0`);
  const aDeleted = await runAndWait(A, "confirm-deletions");
  check("confirmed deletion finished", ["Finished", "Up to date"].includes(aDeleted), aDeleted);
  check("the server now holds a tombstone", (await serverEntry("bin/data.bin"))?.deleted === true);

  const bProposed = await runAndWait(B, "sync");
  const bUi = await deletionUi(B);
  check("B is offered the deletion and keeps its file until it confirms", bUi?.button === true && (await B.eval(READ_TREE))["bin/data.bin"] === sha(bin), `${bProposed} ${JSON.stringify(bUi)}`);
  await B.eval(`window.confirm = () => true; 0`);
  await runAndWait(B, "confirm-deletions");
  const bAfter = await B.eval(READ_TREE);
  check("B's copy was removed after confirmation, its folder and other files stay", !("bin/data.bin" in bAfter) && "bin/" in bAfter && bAfter["a.txt"] === sha(edited), Object.keys(bAfter).join(","));

  check("no uncaught browser errors", A.consoleErrors().length === 0 && B.consoleErrors().length === 0, JSON.stringify([...A.consoleErrors(), ...B.consoleErrors()].slice(0, 1)));
} catch (e) {
  console.error(e);
  console.error(serverLog.slice(-3000));
  failures++;
}
console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
process.exit(failures ? 1 : 0);
