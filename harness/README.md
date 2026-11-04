# FolderSync feasibility harness (Roadmap Step 1)

Standalone, dependency-free harness that exercises the real File System Access API on the supported OS/browser
combinations and produces the evidence required by the Step 1 exit gate. It deliberately shares no code with the
future application (Step 2 onward), except that the incremental SHA-256 in `public/lib/sha256.js` and the hash
worker are candidates to carry over once measured.

Requires Node 22+ (only for the helper scripts) and desktop Chrome or Edge.

## Layout

| Path | Purpose |
|---|---|
| `server.mjs` | Static server (HTTP on localhost, or HTTPS with `--cert/--key`); `PUT /results/<name>.json` stores results |
| `public/` | The harness page: tests in `tests/`, shared code in `lib/`, `hash-worker.js`, lifecycle panel, manual checklist |
| `fixtures/generate.mjs` | Deterministic fixture generator (1k / 10k / 25k files, excluded trees, boundary files, links) |
| `tools/race-injector.mjs` | External-editor simulator for the race test |
| `tools/report.mjs` | Builds `docs/feasibility/compatibility-matrix.md` from `results/*.json` |
| `tools/smoke.mjs` | Headless Chrome smoke run against OPFS (proves the code runs; **not** evidence) |
| `test/` | `node --test` suite for the SHA-256 implementation and the fixture generator |

## Run on each machine

```sh
cd harness
node server.mjs                       # http://127.0.0.1:8443/  (localhost is a secure context)
```

For a test machine that is not the server host, serve over HTTPS (File System Access needs a secure context):
`node server.mjs --host 0.0.0.0 --cert cert.pem --key key.pem` with a certificate trusted by that machine
(e.g. created with `mkcert`).

### 1. Generate fixtures (outside this repository)

```sh
node fixtures/generate.mjs --out ~/fsync-fx/1k  --files 1000  --seed 1 --links
node fixtures/generate.mjs --out ~/fsync-fx/10k --files 10000 --seed 2
node fixtures/generate.mjs --out ~/fsync-fx/25k --files 25000 --seed 3 --target-bytes 1073741824 --boundary
```

* Output is byte-identical for the same arguments; `.fixture-manifest.json` holds the expected SHA-256 and size of every included file.
* Excluded trees (`node_modules`, `build`, `target`, `.gradle`, `.git`, `web/dist`) hold 2× the included file count (max 50,000) and are never listed in the manifest.
* `--boundary` adds `boundary/exact-limit.bin` (104,857,600 B) and `boundary/over-limit-by-one.bin` (+1 B). They take ~200 MiB.
* `--links` creates `__links__/` with a file symlink, a directory symlink (junction on Windows), a dangling link, a self-loop and a link to the parent. On Windows creating symlinks needs Developer Mode or an elevated shell; `manifest.links[].created` records what could be created. Create junctions manually with `mklink /J` if needed.
* Run the generator on each test machine with the same arguments rather than copying large trees between machines; the output is byte-identical.

### 2. Run the automated tests

1. Open the harness page in the browser, click **Select folder**, choose a fixture folder (or an empty disposable folder for non-perf tests).
2. Set a **Scenario label** (e.g. `1k`, `after-reload`, `injector=replace`) so runs are distinguishable.
3. Run every test card. Tests that need no folder (`locks`, `handle-reload`) work without a selection.
4. Race test: in a terminal run `node tools/race-injector.mjs <selected folder> append`, run the test, then repeat with `truncate-write` and `replace`.
5. Persistence: run *Store the root handle*, then reload, restart the browser, and finally clear site data, running *Reconnect* each time (label each run).
6. Permission revocation, restricted folders, tab suspension and the other **Manual scenarios** are filled in on the page.
7. Perf: run 1k, 10k, 25k fixtures (the 25k run is the 25,000-file / 1 GiB acceptance fixture) at least three times each; try 1/4/8 workers, `slice` vs `stream`, and parallel directory listings 1/4.
8. **Download results JSON** (or *Save to harness/results/*). File names encode OS, browser and major version.

### 3. Produce the matrix

```sh
node tools/report.mjs
```

This rewrites `docs/feasibility/compatibility-matrix.md`. The exit gate needs evidence for all six combinations:
Windows/Linux/macOS × Chrome/Edge. The matrix header reports how many are covered.

## Safety

Mutating tests only write inside `<selected folder>/__harness_scratch__` (recreated on every run). The *links* test
can optionally call `removeEntry` on entries in `__links__` (opt-in checkbox) to establish what removing a link does;
the sentinel file proves whether the link target survived. Never select a folder with real data.

## Provisional constants (to be replaced by measured budgets)

The pass thresholds embedded in tests (`< 64 MiB` JS heap growth, `< 500 ms` cancel latency) are placeholders chosen
to catch whole-file buffering and unresponsive cancellation. The actual budgets belong in
`docs/feasibility/performance-budgets.md` once measurements exist.

## Known limits of the harness

* `performance.memory` reports JS heap only (Chromium, coarse). Use the browser task manager for process memory, and note it in the scenario.
* The Chrome `*.crswap` staging sibling may be visible while a writable is open; the test records this so the scanner can be taught to ignore it.
* There is no link detection API; the *links* test only records what is observable. Do not claim link skipping from the browser until it has been shown to be possible.
* Simulated external edits come from `race-injector.mjs`; real editors (VS Code, IntelliJ, Notepad, TextEdit, vim) must be exercised manually.
