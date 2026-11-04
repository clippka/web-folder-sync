# Browser feasibility report (Roadmap Step 1)

**Status: NOT STARTED — no evidence has been collected on any supported OS/browser combination.**
The harness, fixture generator and report tooling exist (`harness/`); only a headless OPFS smoke run on macOS Chrome
has executed the harness code, which is not feasibility evidence. This document must be completed from
`harness/results/*.json` and `compatibility-matrix.md` before Step 2 proceeds.

Specification: sections 2, 7, 8, 14–15. Exit gate: evidence for all six combinations; every safety-critical gap
resolved or returned for a specification decision; proof that reopening or focusing a page does not restart work.

## 1. Environments tested

| Combination | Browser version | OS version | Date | Operator | Result files |
|---|---|---|---|---|---|
| Windows / Chrome | | | | | |
| Windows / Edge | | | | | |
| Linux / Chrome | | | | | |
| Linux / Edge | | | | | |
| macOS / Chrome | | | | | |
| macOS / Edge | | | | | |

## 2. Findings per roadmap item

Fill each with: result, evidence (result file + test id), impact on the design, decision needed (if any).

| Roadmap item | Harness test | Finding | Status |
|---|---|---|---|
| Directory selection, real read/write capability | `capabilities` | | open |
| Handle persistence, identity checks, overlap detection | `handle-persist`, `identity-other` | | open |
| Reconnect after reload / restart / permission loss / cleared data | `handle-reload`, manual `permission-*`, `clear-site-data` | | open |
| Restricted folders | manual `restricted-folders` | | open |
| Exclusive binding lock across tabs; release on close | `locks` | | open |
| Pause/cancel, navigation, suspension, closure, explicit resume | lifecycle panel, manual `tab-suspension`, `browser-closure-resume` | | open |
| Worker incremental SHA-256, bounded reads, 100 MiB boundary | `hashing` | | open |
| Staged writes, abort, post-write verification | `staged-write` | | open |
| Symlinks/junctions/inaccessible entries | `links`, manual `windows-links` | | open |
| Case/Unicode collisions, name restrictions, depth | `entries` | | open |
| Nested empty directories, nonrecursive removal | `entries`, `staged-write` | | open |
| External-editor races (before / between recheck and write / during) | `race` + manual `editor-save-behaviour` | | open |
| Enumeration/hashing/memory/cancel/time-to-first-progress at 1k/10k/25k | `perf` | | open |
| 25,000 files / 1 GiB in Chrome and Edge | `perf` | | open |

## 3. Early observations (not yet verified on target platforms)

These come from the macOS headless OPFS smoke run only and must be re-verified on real folders:

* Chrome exposes a `<name>.crswap` sibling in directory enumeration while a writable stream is open. The scanner would need to ignore it, and an abandoned writer (page closed mid-write) may leave it behind. **Verify on disk on every OS.**
* Opening a second writable on the same file is allowed; the last `close()` wins, with no error to either writer.
* `removeEntry` without `recursive` fails with `InvalidModificationError` for non-empty directories, including those containing only an empty subdirectory.
* NFC and NFD spellings, and names differing only by case, can coexist on the OPFS volume. The file system under a user-selected folder may fold them (macOS APFS, Windows NTFS). Verify before relying on the collision rules.

## 4. Remaining race window (to be measured)

Specification section 7 forbids claiming atomic compare-and-swap. Record here, per OS/browser and per external-writer
mode (`append`, `truncate-write`, `replace`): the measured exposure window (recheck → close), and for each of the three
injection phases whether the external edit survived, was silently lost, or the OS refused it. Then state the exact
user guidance for the UI and help text.

## 5. Unsupported or unprovable behaviour

List behaviours the browser cannot support or detect (link detection, junction handling, long paths, etc.) so Step 3/5
report them explicitly instead of claiming support.

## 6. Decisions returned to the specification

None recorded yet. Any safety-critical capability gap goes here with a proposed specification change.

## 7. Decisions for later steps (roadmap "Decisions to resolve")

| Decision | Candidate options | Evidence needed | Chosen |
|---|---|---|---|
| Streaming hash implementation | pure-JS (`harness/public/lib/sha256.js`, verified vs node:crypto) vs WASM | MiB/s per OS/browser from `hashing` and `perf`; start with JS and switch only if the 25k/1 GiB budget is missed | |
| Read mode | `slice` vs `stream` | `hashing` throughput and memory | |
| Hash worker count | 1/2/4/8 | `perf` | |
| Parallel directory listing | 1 vs 4+ | `perf` | |
| Upload chunk size / concurrency | 8 MiB × 4 proposed | Step 7, informed by hashing/read measurements | |
| Supported browser versions | | all result files | |

## 8. Numeric performance budgets

See `performance-budgets.md` (to be derived from measurements; none is claimed yet).
