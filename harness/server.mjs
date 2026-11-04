#!/usr/bin/env node
// Zero-dependency static server for the feasibility harness.
//   node server.mjs [--port 8443] [--host 127.0.0.1] [--cert cert.pem --key key.pem]
// http://localhost / 127.0.0.1 is a secure context, which is enough for the File System Access API.
// For testing from another machine use --cert/--key (e.g. created with mkcert) so the page is served over HTTPS.
// PUT /results/<name>.json stores a result file under harness/results/.
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(here, 'public');
const resultsDir = path.join(here, 'results');
const { values } = parseArgs({ options: { port: { type: 'string', default: '8443' }, host: { type: 'string', default: '127.0.0.1' }, cert: { type: 'string' }, key: { type: 'string' } } });

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml' };
const HEADERS = {
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; worker-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'",
};

function handler(req, res) {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'PUT' && url.pathname.startsWith('/results/')) {
    const name = path.basename(url.pathname);
    if (!/^[\w.-]+\.json$/.test(name)) { res.writeHead(400, HEADERS).end('bad name'); return; }
    const chunks = [];
    let size = 0;
    req.on('data', (c) => { size += c.length; if (size > 20e6) req.destroy(); else chunks.push(c); });
    req.on('end', () => {
      fs.mkdirSync(resultsDir, { recursive: true });
      fs.writeFileSync(path.join(resultsDir, name), Buffer.concat(chunks));
      res.writeHead(204, HEADERS).end();
    });
    return;
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405, HEADERS).end(); return; }
  let rel = decodeURIComponent(url.pathname);
  if (rel.endsWith('/')) rel += 'index.html';
  const full = path.resolve(publicDir, '.' + rel);
  if (!full.startsWith(publicDir + path.sep) || !fs.existsSync(full) || !fs.statSync(full).isFile()) { res.writeHead(404, HEADERS).end('not found'); return; }
  res.writeHead(200, { ...HEADERS, 'Content-Type': MIME[path.extname(full)] ?? 'application/octet-stream' });
  if (req.method === 'HEAD') res.end(); else fs.createReadStream(full).pipe(res);
}

const secure = values.cert && values.key;
const server = secure
  ? https.createServer({ cert: fs.readFileSync(values.cert), key: fs.readFileSync(values.key) }, handler)
  : http.createServer(handler);
server.listen(Number(values.port), values.host, () => {
  console.log(`Harness at ${secure ? 'https' : 'http'}://${values.host}:${values.port}/`);
  if (!secure && !['127.0.0.1', 'localhost', '::1'].includes(values.host)) console.warn('WARNING: not a secure context unless served over HTTPS or via localhost.');
});
