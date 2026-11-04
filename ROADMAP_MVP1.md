# FolderSync MVP1 implementation roadmap

Source: [FolderSync specification v1.0](docs/FolderSync_Specification.md), dated 2026-11-04.  
Status: Planned. No implementation or release gate is complete.  
Repository baseline: Specification only; application, build, tests, and deployment files must be created.

## Scope and delivery rules

MVP1 implements the specification's complete v1 contract. The milestones below are development increments, not reductions in release scope.

- Deliver private, owner-only sync spaces with multiple accounts and devices, exact-byte two-way synchronization, and nested empty directories.
- Use Kotlin/Java 21, Spring Boot MVC/Security/JDBC, SQLite, immutable filesystem blobs, Thymeleaf/HTMX, and a dedicated TypeScript browser coordinator.
- Support desktop Chrome and Edge on Windows, Linux, and macOS, subject to measured browser feasibility. Production uses HTTPS.
- Only explicit Sync now or Resume sync actions start mutations. Scan is an optional non-mutating preview. Apply unambiguous additions and updates without full-plan confirmation; conflicts and deletions require decisions.
- Support 25,000 included files per space and a configurable default maximum file size of 104,857,600 bytes. Users have no quota by default; optional quotas apply across all of an owner's spaces.
- Preserve current files and unresolved conflict candidates, but provide no historical content, trash, undo, or per-file restore. Retain compact tombstones indefinitely.
- Deliver a container image and Docker Compose deployment for one application instance. No standalone JAR distribution, separate database service, or multi-replica support.
- Exclude sharing, automatic/background sync, Git operations, automatic text merge, rename detection, binary deltas, and filesystem metadata preservation.

Complete each milestone's exit checks before dependent work is treated as ready. Browser uncertainty is resolved first. Deletion planning can be developed early, but deletion execution must remain disabled until the safety gate in Step 8 passes.

## Step 1 — Validate browser feasibility and define measurable targets

**Depends on:** Nothing.  
**Specification:** Sections 2, 7, 8, 14–15.

- [ ] Build a small HTTPS browser harness using real File System Access handles and representative project fixtures.
- [ ] Test directory selection, actual read/write capability, IndexedDB handle persistence, identity checks, permission revocation, reconnect, restricted folders, and cleared browser data.
- [ ] Test exclusive binding locks across tabs, pause/cancel behavior, navigation, page suspension, browser closure, and explicit resume.
- [ ] Verify worker-based incremental SHA-256, bounded file reads, staged writes/abort behavior, post-write verification, and 100 MiB boundary files without whole-folder buffering.
- [ ] Exercise symlinks, junctions, inaccessible entries, case/Unicode collisions, nested empty directories, and nonrecursive removal on all supported OS/browser combinations.
- [ ] Inject simultaneous external-editor writes before and during replacement. Record the remaining race window and required user guidance; do not claim atomic compare-and-swap on local files.
- [ ] Measure enumeration, hashing, memory, cancellation responsiveness, and time to first progress using 1,000-, 10,000-, and 25,000-file fixtures with excluded dependency trees.
- [ ] Record browser/OS versions, test procedures, results, and numeric performance budgets derived from measurements. Identify unsupported link behavior explicitly.

**Deliverables:** Browser harness, reproducible fixture generator, compatibility matrix, feasibility report, and performance budgets.

**Exit gate:** Evidence exists for all six OS/browser combinations. Any safety-critical capability gap is resolved or returned for a specification decision before dependent implementation proceeds. The harness must demonstrate that reopening or focusing a page does not restart work.

**Progress (2026-11-04):** Tooling is built; **no checklist item above is complete and the exit gate is not met** because no run has happened on a real user-selected folder or on any of the six OS/browser combinations.
- Built: `harness/` (test page, hash worker, lifecycle panel, manual checklist, race injector, results server), `harness/fixtures/generate.mjs` (exact 1k/10k/25k/1 GiB fixtures, boundary and link fixtures), `harness/tools/report.mjs`, and report skeletons in `docs/feasibility/` (`compatibility-matrix.md`, `feasibility-report.md`, `performance-budgets.md`). See `harness/README.md` for the run procedure.
- Verified locally: SHA-256 and fixture-generator unit tests (`cd harness && npm test`), and a headless macOS Chrome run of the capability, hashing, staged-write, entries, handle-persistence and perf code paths against OPFS. OPFS is not a user-visible folder, so this proves the harness runs, not browser feasibility.
- Never executed: the `locks` popup test, `race` (needs a real folder plus the injector), `links`, real-folder `perf`, and every Windows, Linux and Edge run.
- Next action: run the harness procedure on the six combinations, then run `node harness/tools/report.mjs` and complete the feasibility report and performance budgets.

## Step 2 — Establish the build and application skeleton

**Depends on:** Step 1's architectural findings.  
**Specification:** Sections 9, 15.

- [ ] Select and pin supported Spring Boot, Kotlin, Gradle, frontend tooling, and test dependencies at implementation time; record choices and compatibility.
- [ ] Add `settings.gradle.kts`, `build.gradle.kts`, and the complete checked-in Gradle Wrapper.
- [ ] Configure TypeScript type checking and esbuild through Gradle, with a pinned package manager and dependency lockfile; package generated assets into Spring Boot.
- [ ] Establish server boundaries for identity, spaces, manifests, policy, pure reconciliation, transfers, revision metadata, journals, blob cleanup, and audit.
- [ ] Establish browser modules for permissions/bindings, IndexedDB, scanning, hashing workers, reconciliation orchestration, transfers, journals, and UI status.
- [ ] Add the Thymeleaf/HTMX shell with the coordinator outside swapped fragments and locally bundled scripts.
- [ ] Add unit/integration test infrastructure and CI for repeatable wrapper builds on Windows and Linux. Document clean build and developer startup commands.

**Deliverables:** Buildable application skeleton, frontend bundle, test harnesses, and CI configuration.

**Exit gate:** Clean wrapper builds compile Kotlin, type-check/bundle TypeScript, run the initial tests, and package the assets on Windows and Linux.

**Progress (2026-11-04):** Local wrapper build passes on Windows (`gradlew build`: npm ci, tsc, esbuild, Kotlin, frontend and Spring tests, bootJar with bundled assets). Not yet verified: CI on Windows and Linux (workflow exists, has not run), so the exit gate is not met.
- Done: pinned versions recorded in `docs/BUILD.md`; `settings.gradle.kts`, `build.gradle.kts`, wrapper; `frontend/` with lockfile, `tsc --noEmit` and esbuild wired through Gradle; server module packages and browser module directories; Thymeleaf shell with the coordinator outside the swap target and a deny-by-default security stub with CSP.
- Remaining: CI evidence on both OSes; module contents belong to later steps (hash library awaits Step 1 measurements).

## Step 3 — Implement the pure reconciliation, path, and ignore-policy core

**Depends on:** Step 2.  
**Specification:** Sections 4–7, 12.

- [ ] Define explicit absent/file/directory states, baseline versions, scan completeness, suspended/blocked entries, expected local hashes, policy revisions, and immutable plan operations.
- [ ] Implement the full three-way reconciliation table as a pure Kotlin component, including first enrollment, identical concurrent edits, concurrent creation, delete-versus-edit, and both-side deletion.
- [ ] Model structural conflicts and subtree dependencies; create parents before children and prevent dependent operations while unrelated paths remain executable.
- [ ] Treat rename as add plus delete. Preserve empty directories and distinguish deleting a directory from removing its last file.
- [ ] Implement canonical portable relative-path validation, traversal rejection, reserved names, depth limits, and case/Unicode collision detection before transfer.
- [ ] Implement root-to-leaf `.gitignore` then `.syncignore` matching, deeper-rule precedence, escapes, negation, parent exclusion, and matched-rule explanations.
- [ ] Enforce non-overridable exclusion of every `.git` path segment, including `.git` files; add no hidden dependency/build-directory exclusions.
- [ ] Treat traversable ignore files as synchronized control files that cannot exclude themselves. Model control-file reconciliation before ordinary content, space-wide pauses for policy conflicts, frozen policy revisions, and rescan after policy changes.
- [ ] Preserve baselines and copies when paths become ignored or oversized. Re-inclusion must not treat absence during suspension as automatic deletion intent.
- [ ] Create shared browser/server ignore conformance fixtures and table-driven planner tests, including incomplete scans and missing baselines.

**Deliverables:** Pure planner, portable path policy, policy matchers, and shared semantic fixtures.

**Exit gate:** Every reconciliation-table row and structural-conflict case has deterministic tests. Browser and server agree on ignore fixtures. Incomplete scans cannot produce deletion plans; ignore/size changes cannot turn into deletions.

**Progress (2026-11-04):** Core implemented and tested locally (`gradlew clean build` green: 58 Kotlin tests incl. 39 planner, 15 ignore-conformance, 4 path; 14 frontend tests). Exit gate largely met; the unchecked items are not fully done.
- Done: explicit-state model and the full ten-row table, first enrollment and missing baseline (never deletion), incomplete/unreadable scans (no deletions), suspension and re-inclusion, structural conflicts and subtree blocking, parent-before-child / child-before-parent ordering, directory-deletion conflicts, control-file phase with pause and rescan-required; portable path validation and case/Unicode collision detection; gitignore matcher with `.gitignore`→`.syncignore` layering, matched-rule explanations, mandatory `.git` exclusion, self-exempt control files; shared fixtures in `conformance/ignore-fixtures.json` verified by both the Kotlin and TypeScript matchers.
- Not done: TypeScript port of the portable-path validator (no shared path fixtures yet); the planner takes suspension/re-inclusion as input and does not itself decide oversize or compute ignore transitions; policy-revision freezing is only carried on the plan; deterministic Keep-both naming landed in Step 6.

## Step 4 — Build secure server persistence and ownership boundaries

**Depends on:** Steps 2–3.  
**Specification:** Sections 3, 8–12.

- [x] Add versioned SQLite migrations for users, spaces, devices, bindings, entries, revisions, metadata-only entry changes, uploads, runs, conflicts, and transfer leases.
- [x] Add persistence for immutable plans, idempotency outcomes, quota reservations, acknowledgements, and a reconciliation epoch; finalize missing protocol fields before browser integration.
- [x] Keep database transactions short and separate from content transfer. Serialize commits per space and apply optimistic entry/space/policy preconditions.
- [x] Implement password hashing, server sessions, fixation protection, secure cookies, CSRF, login rate limiting, logout, password changes, and mounted-secret administrator bootstrap without default credentials or public signup.
- [x] Implement space/device/binding ownership checks for every nested resource. Administrator privileges must not grant access to another user's sync content.
- [x] Create generated-ID immutable blob storage outside the web root, staging, reference accounting, renewable bounded operation leases, and cleanup.
- [x] Authorize downloads through current entries, unresolved candidates, or eligible active operations; reject obsolete-content access immediately even before physical cleanup.
- [x] Define stable paginated manifest snapshots, bounded manifest ingestion, change feeds, JSON errors, retryability, and all route contracts in specification Section 11. Specify snapshot expiry/replan behavior without retaining historical file bytes.
- [x] Design and test publication crash boundaries: finalized blob before metadata reference, transactional revisions, orphan cleanup, and restart consistency.
- [x] Implement metadata-only audit records without absolute local paths, file contents, passwords, or session credentials.
- [x] Define consistent metadata-plus-referenced-blob backup procedures and a restore workflow that changes the reconciliation epoch before clients reconnect.

**Deliverables:** Secured persistence/services, migrations, API contracts, storage lifecycle, and backup/epoch design.

**Exit gate:** Integration tests reject cross-user access using known resource IDs, including admin attempts, uploads, plans, conflicts, leases, and blobs. Stale commits fail safely. Restart and blob-publication failure tests preserve referenced content and metadata consistency.

**Progress (2026-11-04):** Implemented and tested locally; `gradlew build` is green (135 tests: 58 existing Kotlin, 77 new server integration tests, plus the frontend tests). Contracts, decisions and the error-code table are in [docs/SERVER.md](docs/SERVER.md). CI on Windows and Linux has still not run.
- Done: `V1__baseline.sql` and an in-house checksummed migrator; SQLite in WAL with immediate write transactions; users, spaces, devices, bindings, entries with indefinite compact tombstones, revisions and metadata-only change log, uploads, runs, immutable plans, conflicts, plan-scoped leases, idempotency outcomes, quota reservations, acknowledgements, audit and the reconciliation epoch; login/logout/password change with CSRF, fixation protection, throttling and mounted-secret administrator bootstrap; owner-only access with 404 for foreign resources (administrators included); chunked uploads with server-side size and digest checks; derived blob reference rules, leases, cleanup and orphan sweeping; every Section 11 route; stable paginated manifest snapshots, change feed and SSE notifications; backup (`VACUUM INTO` plus referenced blobs) and restore that rotates the epoch.
- Verified by tests: cross-user and administrator attempts on every resource type with known IDs; stale plans, policies, conflict decisions and epochs; idempotent replay of commit and upload begin; blob published before its row, failed commit leaves nothing applied, restart on the same data directory, startup failures; 100 MiB boundary in 8 MiB chunks, one byte over, chunk-splitting bypass; quota across spaces under concurrent reservation; tombstones, stale-device deletion proposals and directory rules with the deletion gate open.
- Found by the tests and fixed: the change feed returned the whole boundary revision when no path cursor was given.
- Decisions made: space-revision movement alone does not fail a commit; per-entry version guards, parent checks and the policy revision do (a stale guard fails the whole commit and says to replan). Server computes ignore and oversize suspension itself. Deletion approvals stay rejected (`foldersync.deletion-enabled=false`) until Step 8.
- Not done / limits: backup and restore are library calls with a documented procedure, not yet an admin command or endpoint (Step 10 runbook). No SQLite contention or disk-full fault injection yet (Step 7). `Secure` cookies and forwarded-header handling are untested behind a real HTTPS proxy (Step 10).


## Step 5 — Deliver the first-sync vertical slice

**Depends on:** Steps 1–4.  
**Specification:** Sections 2–4, 6–7, 10–11, 13.

- [x] Implement login, space creation/list/detail, Connect local folder, optional device labels, Disconnect, and Reconnect.
- [x] Persist handles and per-path state in IndexedDB, namespaced by server origin and user ID. Reject duplicate/overlapping active bindings where handle APIs permit detection.
- [x] Treat cleared browser data, unknown identity, or enrollment-generation mismatch as fresh non-destructive enrollment. Explain the separate-profile overlap limitation.
- [x] Implement full enumeration with explicit missing/ignored/unreadable/failed outcomes, early subtree pruning, directory entries, streaming worker hashing, and bounded concurrency.
- [x] Implement the control-file phase and server policy validation before ordinary content planning.
- [x] Implement Scan as preview only and Sync now as an explicit single run. Enforce the browser binding lock and prevent duplicate coordinators/workers after HTMX navigation.
- [x] Transfer first-sync additions both ways, adopt identical content without transfer, queue same-path differences as conflicts, and propagate nested empty directories.
- [x] Verify upload hashes server-side and resulting local bytes before persisting successful per-path baselines and acknowledgements.
- [x] Surface per-file progress, ignored and unsupported paths, oversized files, partial success, permission requirements, cancellation, and connection failures.

**Deliverables:** Working browser-to-server-to-second-browser first sync, including non-mutating preview.

**Exit gate:** Two machines exchange exact bytes and empty directories. Joining empty or partially populated folders never deletes existing data. Page load, connect, focus, network return, notifications, and timers never start sync. Unsupported browsers can use account/server pages but cannot run local sync.

**Progress (2026-11-04):** Implemented and verified locally; the exit gate is **not fully met** because every browser run so far used OPFS folders in headless Chrome 156 on Windows, never a user-selected folder, Edge, Linux or macOS. Design and decisions are in [docs/CLIENT.md](docs/CLIENT.md). `gradlew build` is green (Kotlin 141 tests incl. the new page tests, frontend 34 tests).
- Done: Thymeleaf/HTMX space list, create (form), detail panel with Connect, optional device label, Reconnect, Disconnect, Scan, Sync now, Cancel; `limits` added to the space API; coordinator, IndexedDB store (per user), binding lock, overlap detection, ignore-aware scan with pruning and worker hashing, plan batching, control-file passes with rescan, chunked uploads, verified streamed downloads, adoption, per-file progress and result summary; unsupported-browser notice; help text on cleared data, separate profiles and the editor race.
- Evidence: `e2e/two-browsers.mjs` (real jar, two Chrome profiles): exact CRLF/binary bytes and nested empty directories both ways, ignored paths never uploaded, scan changed nothing, connect/reopen started nothing, a no-change run transferred no content.
- Not verified: user-selected folders, permission prompts and revocation, Edge and other OSes (Step 1), the cross-tab lock with two real tabs, overlap detection and Reconnect in a browser, behavior after clearing site data, huge folders (25,000 files / 1 GiB; Step 10).
- Known limits: deletions are listed for review but never applied (Step 8); conflicts are kept as candidates (the resolution UI arrived in Step 6); a run killed by navigation is simply restarted by the user, without journals or resume (Step 7); the browser has no TypeScript port of the portable-path validator, so invalid names are rejected by the server and listed as unsupported; the SSE indicator and "Offline" status are Step 9.



## Step 6 — Add incremental reconciliation and durable conflict review

**Depends on:** Step 5.  
**Specification:** Sections 5–8, 11–14.

- [x] Integrate acknowledged baselines, entry revisions, change feeds, and indefinitely retained tombstones for later runs and long-offline devices.
- [x] Execute unambiguous updates immediately within the clicked run. Replan on stale revisions/policies without unconditional overwrite.
- [x] Preserve both conflicting current variants durably, staging the local candidate when required. Quota/staging failures leave the conflict unresolved and existing files intact.
- [x] Implement Keep local, Keep server, Keep both, and Skip with expected-version checks; use valid unique sibling names for Keep both.
- [x] Implement delete-versus-edit review as Keep modified file or Confirm deletion. Keep deletion execution disabled until Step 8 passes.
- [x] Show size, hash, device, and recorded times; escape and limit text previews and force binary attachment downloads.
- [x] Ensure conflict decisions release unchosen candidates only after successful resolution. Audit explicit candidate discard and provide no undo/history UI.
- [x] Build deletion review showing paths, count, direction, and irreversibility; emphasize batches of at least 100 files or more than 20% of active files, with configurable thresholds.
- [x] Verify conflicts block only dependent paths, except conflicting ignore control files, which pause the affected space.

**Deliverables:** Multi-device updates, durable conflict candidates and resolution UI, tombstones, and deletion proposals.

**Exit gate:** Concurrent edits preserve candidates across browser/server restarts. Matching edits converge. Stale conflict decisions require refresh. Unrelated updates complete while decisions remain pending. Physical retention never enables obsolete-content downloads.

**Progress (2026-11-04):** Implemented and verified by automated tests; the exit gate is **not fully met** because the review UI has never been exercised in a real browser (no DOM or e2e test of the conflict panel, and no two-browser conflict run). `gradlew build` is green (Kotlin 154 tests, frontend 45). Contracts are in [docs/SERVER.md](docs/SERVER.md) (Conflicts and decisions) and [docs/CLIENT.md](docs/CLIENT.md) (Conflict review and decisions).
- Done: Keep both on the server (deterministic sibling name `name (conflict <device> <date>[ n]).ext`, valid, unique, not ignored, within the file limit, rolled back on failure); each conflict reports the `options` it accepts, `serverUpdatedAt` and `bindingId`; decisions that would delete answer `deletion_disabled` while the gate is closed, so delete-versus-edit offers *Keep modified file* and *Confirm deletion* but only the first can succeed; bounded, binary-safe text preview endpoint and attachment-only downloads; the browser conflict panel (both variants side by side, on-demand previews via `textContent`, per-kind wording, cross-tab lock, local pre-checks before anything is recorded, verified local writes, baselines only for verified bytes); deletion review with direction, count, irreversibility note and configurable emphasis (100 files or more than 20%), display only.
- Verified by tests: Keep both/local/server/skip with stale-version refusal, idempotent replay and survival through cleanup; unrelated paths commit while a conflict stays open and re-planning reuses the open conflict; foreign users get 404 for preview and resolve; previews of large, binary, invalid-UTF-8 and script-like files; naming collisions, long and multibyte names; browser refusals when the folder changed, failed writes after a recorded decision, and stale or refused decisions. Candidates surviving a server restart were already covered by `RestartTest`.
- Not done / limits: the client still compares against the full server manifest each run and does not read the change feed (correct, but not incremental on the wire); the recorded time is when the conflict was detected, not the local file's modification time (not captured by the protocol); an open conflict keeps its first recorded candidate when the same path is scanned again, so after a newer local edit *Keep this folder's version* uploads the older candidate and the newer edit follows on the next sync; deletion approval is not wired in the browser (Step 8); a conflict on an ignore control file can only be decided as Keep local or Keep server (no Keep both); no CI, Edge or non-Windows evidence.

## Step 7 — Implement transfer recovery, operation journals, and quotas

**Depends on:** Steps 5–6.  
**Specification:** Sections 7–8, 10–12.

- [x] Complete resumable fixed-size chunk uploads, initially targeting 8 MiB chunks and four concurrent transfers, subject to Step 1 measurements.
- [x] Persist upload IDs, chunk state, size/digest expectations, expiries, and idempotency keys. Identical duplicate chunks succeed; inconsistent duplicates fail; final digest verification precedes publication.
- [x] Stream authorized downloads under bounded leases and replan expired operations against current state.
- [x] Write IndexedDB journals before local mutation. Recheck expected local bytes immediately before writes, verify results, and advance baselines only after successful completion.
- [x] On explicit resume, compare actual content with expected and intended states; recognize completed operations or raise conflicts without false acknowledgements.
- [ ] Handle pause, logout, offline state, revoked permissions/devices, browser shutdown, and server restart. Allow bounded retries only while the user-started run remains active.
- [x] Reserve quota atomically across an owner's spaces for current files, unresolved candidates, and staged transfers; convert/release reservations safely on publication, cancellation, and expiry.
- [x] Enforce complete-file size limits using declared and received bytes, included-file count limits, and bounded memory. Continue eligible paths while preserving blocked-path copies/baselines.
- [ ] Distinguish quota exhaustion from physical disk exhaustion. Lowered quotas preserve existing content and allow downloads/confirmed deletions; removing the quota permits uploads again.

**Deliverables:** Resumable transfers, persistent operation recovery, safe retries, lifecycle controls, and quota accounting.

**Exit gate:** Inject lost responses, duplicate requests, interrupted writes, full disk, expired staging, SQLite contention, concurrent uploads, and process termination at commit/acknowledgement boundaries. Recovery neither duplicates commits nor advances unsuccessful baselines. Exact-limit files succeed; files one byte larger and chunk-based limit bypasses fail safely.

**Progress (2026-11-04):** Implemented and verified by automated tests; the exit gate is **not fully met** (see limits). `gradlew build` is green (Kotlin 161 tests incl. 7 new in `TransferRecoveryTest`, frontend 52 incl. 7 new in `recovery.test.mjs`). Design is in [docs/CLIENT.md](docs/CLIENT.md) (Interruption and resume) and [docs/SERVER.md](docs/SERVER.md) (Resuming after interruption).
- Done: IndexedDB v2 with a write-ahead journal and per-path upload records; journal written before a local replacement, per-file baseline stored before the entry is released; explicit Scan/Sync settles leftover entries from the actual bytes (completed, never started, unknown -> baseline dropped so it can only adopt or conflict); a last look at the target immediately before the swap; uploads continue with the same idempotency key and send only missing chunks, stale/used/expired/changed uploads start fresh; `consumed` on the upload status; *Resume sync* label; cancel keeps resumable state; server-side chunk, digest, limit, quota and expiry behavior from Step 4 re-verified, plus concurrent identical chunks, 24 parallel uploads, lost commit/ack replies.
- Not done / limits: pause as a separate state and logout/revoked-device shutdown (Step 9; Cancel plus *Resume sync* covers pause today); full-disk and killed-process injection are not exercised (the 507 `insufficient_disk_space` path exists but no test makes the disk full, and process termination at commit/ack boundaries is covered only by restart tests and the simulated crash of the client store); chunk size 8 MiB and four concurrent transfers are unmeasured defaults awaiting Step 1 hardware runs; no real-browser run of resume (e2e does not interrupt a transfer); the editor race between the last look and the swap remains and is documented rather than closed; a staged-but-never-resumed upload keeps its quota reservation until `upload-expiry` (24 h).

## Step 8 — Pass the safety gate and enable confirmed deletion

**Depends on:** Steps 3–7 and their passing recovery/concurrency tests.  
**Specification:** Sections 4–7, 14–15.

- [x] Run fault-injection coverage for incomplete enumeration, root disconnect, permission loss, stale plans, external edits, lost acknowledgements, suspended paths, and missing baseline state.
- [x] Verify all server and local deletions require explicit approval tied to a displayed, still-valid plan; stale approvals cannot authorize changed operations.
- [x] Enable file deletion only after these tests pass; keep expected-content/version checks immediately before mutation.
- [x] Remove approved children before parents and remove directories nonrecursively only when their own deletion is planned and confirmed.
- [x] Leave directories with ignored/unexpected children intact, report blocked deletion, and retain pending baseline state. Treat opposite-side new/modified descendants as structural conflicts.
- [x] Verify obsolete references are released, leases protect active operations, and cleanup preserves current content and unresolved candidates without creating recovery copies.
- [x] Test backup restoration with an epoch change and mandatory recovery reconciliation; never interpret rolled-back revisions as a valid unchanged baseline.

**Deliverables:** Enabled confirmed deletion, recorded safety-gate evidence, and tested restore reconciliation.

**Exit gate:** No failed/incomplete scan or first enrollment propagates deletion. Deletion and replacement remain safe across tested interruption/concurrency cases, with the measured external-editor race documented. No app-level history, trash, or restore mechanism exists.

**Progress (2026-11-04):** Implemented and verified by automated tests; the exit gate is **not fully met** because the external-editor race is unmeasured and nothing ran on a user-selected folder, Edge, Linux or macOS. `gradlew build` is green; `node e2e/two-browsers.mjs` passes including a confirmed deletion across two Chrome profiles. Evidence table: [docs/SAFETY_GATE.md](docs/SAFETY_GATE.md).
- Done: `foldersync.deletion-enabled` now defaults to `true` (false is a kill switch, covered by tests that run with it off); the browser gets a *Delete these N items for good* action that starts a new explicit sync which plans afresh and approves only listed paths the new plan still proposes (never with the gate closed or an incomplete scan); server deletions drop their baselines after the commit; local deletions run deepest first, recheck the reviewed hash twice, use non-recursive `removeEntry`, and keep path and baseline on any failure; 10 new browser tests (`deletion.test.mjs`) and 4 server tests with the gate open (`SafetyGateTest`).
- Not done / limits: the race between the last check and `removeEntry` is documented, not closed or measured (Step 1 `race` run outstanding); no full-disk or killed-process injection at commit/acknowledgement boundaries; a deletion run replans from a fresh scan, so a change made between review and confirmation simply means that item is not deleted and is proposed again; no in-browser DOM test of the review panel beyond the e2e run.

## Step 9 — Complete product workflows and security verification

**Depends on:** Steps 5–8.  
**Specification:** Sections 9, 12–14.

- [x] Complete account/password, administrator account/reset/quota, device/revocation, and limits workflows.
- [x] Complete space summaries, conflict review, matched-rule explanations, policy-impact preview, activity, and per-file errors with partial-success reporting.
- [x] Implement every specified status, including Permission required, Needs review, Paused, Offline, and Failed; Up to date must show the last completed scan time.
- [x] Add SSE change indicators and polling fallback that never scan, transfer, or resume automatically.
- [x] Explain current-only storage, lack of per-file recovery, server-readable content, manual operation, suspended tabs, unsupported paths, and the concurrent-editor limitation in relevant UI/help.
- [x] Verify restrictive CSP, locally bundled scripts, escaped filenames/previews, attachment downloads, MIME-sniffing protection, CSRF for every mutation, and JSON API authentication errors.
- [ ] Exercise all routes for owner isolation, path validation, cross-user IndexedDB binding isolation, session expiry, and logout coordinator shutdown.

**Deliverables:** Complete v1 screens, help text, and security regression coverage.

**Exit gate:** End-to-end tests cover the documented journeys without requiring a CLI, extension, installed PWA, or agent. Administrative permissions cannot bypass private-space ownership.

**Progress (2026-11-04):** Implemented and verified by automated tests; the exit gate is **not fully met** because the new screens and status badge were exercised only through MockMvc and unit tests, not in a browser journey, and the last checklist item is open. `gradlew build` is green (Kotlin 185 tests incl. 20 new in `ProductWorkflowsTest`, frontend 67 incl. 5 new in `status.test.mjs`); `node e2e/two-browsers.mjs` still passes. Contracts are in [docs/SERVER.md](docs/SERVER.md) (Product workflows).
- Done: pages for account (usage, limits, password change that ends all sessions, privacy note), devices (list and revoke), administrator users (create, reset password, quota in GiB or none, enable/disable; account management only), space list with connected devices, file and conflict counts, per-space ignore rules (rule files, matched-rule explanation with file and line, policy-change preview that stores nothing), per-space activity (metadata only), and a public help page covering manual operation, suspended tabs, current-only storage, server-readable content, unsupported paths and the editor race; API twins under `/api/spaces/{id}/summary|activity|policy|policy/explain|policy/preview` and `/api/account/usage`; a pure `deriveStatus` for all eleven statuses with "Up to date" always carrying the last scan time; an informational server-change indicator (SSE with a 60 s polling fallback) that has no path to start work; the coordinator now stops all runs and the indicator on a 401 or sign-out, and shows Offline from browser online/offline events.
- Verified by tests: CSRF refusal on a hand-listed set of mutating routes (pages, API, admin); 401 JSON for new API routes and sign-in redirect for new pages; administrators and other users get 404 for every new space route and page; CSP, `nosniff`, locally bundled scripts only, no inline handlers; escaped space, rule and activity text; password change and admin reset end old sessions; device revoke stops planning and cannot be used on another user's device; preview changes nothing.
- Not done / limits: no browser journey covers the account, device or admin screens or the rules pages (they are server-rendered forms tested over MockMvc); the status badge, SSE indicator and offline state are unit-tested but not asserted in the e2e run; cross-user IndexedDB isolation and logout shutdown of the coordinator are implemented (per-user database name, `stopAll` on logout/401) but have no automated browser test; the CSRF list is maintained by hand, not generated from the route table; activity shows the server's metadata log, while per-file results of a run remain a browser-side view of the latest run; a Paused state is derived from cancel or leftover journals, there is still no separate pause control; policy changes are previewed from pasted text rather than from a connected folder's edits.


## Step 10 — Package deployment and verify performance and release readiness

**Depends on:** Steps 1–9.  
**Specification:** Sections 8–9, 14–15.

- [x] Build a versioned multi-stage container image from the same Gradle definition, containing only runtime dependencies and packaged assets, running as a non-root user.
- [x] Deliver `compose.yaml`, a secret-free configuration example, mounted administrator-secret configuration, and a persistent `/data` volume for database/sidecars, blobs, and staging.
- [x] Document HTTPS reverse-proxy integration, public origin/trusted proxies, restricted application port, upload/time-out limits, and SSE handling.
- [x] Add health checks, startup migrations, explicit storage initialization failures, and graceful shutdown that stops new commits and preserves recoverable operation state.
- [ ] Test container replacement and ordinary Compose shutdown against the same volume; preserve accounts, current files, unresolved conflicts, bindings, and sync state.
- [x] Document startup, configuration, upgrades, logs, separate destructive volume deletion, and optional consistent backups. Test a complete restore with epoch rotation; do not promise schema downgrade compatibility.
- [ ] Benchmark initial, no-change, and 1%-changed runs for 1,000/10,000/25,000 included files, nested ignore policies, and large excluded trees. Run the 25,000-file/1 GiB fixture in both Chrome and Edge and complete the six-platform compatibility matrix.
- [ ] Record enumeration, hashing, planning, transfer request counts, memory, and time to first progress against Step 1 budgets. Verify excluded descendants are not hashed and directory/ignored entries do not consume the file allowance.
- [ ] Verify no-change runs transfer no file content, sparse runs transfer only changed included content, and all fixtures preserve exact bytes/line endings.
- [ ] With sufficient disk space, verify a new user with no quota can store more than 10 GiB across spaces within per-file/per-space limits; test simultaneous cross-space uploads under configured quotas, quota removal, and lowering below usage.
- [ ] Run the complete acceptance matrix below and attach evidence to the release checklist. Publish supported browser versions and measured limits only after passing.

**Deliverables:** Versioned image, Compose configuration, deployment/runbook documentation, benchmark results, and release acceptance report.

**Exit gate:** All 23 specification acceptance criteria pass with recorded evidence. Production operators need Docker Compose and HTTPS infrastructure, without installing Java, Node.js, Gradle, or a database service on the host.

**Progress (2026-11-04):** Packaging is done and verified locally with Docker 29.5 on Windows; the exit gate is **not met** because benchmarks, the six-platform matrix and the acceptance report need real browsers and hardware. Runbook: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
- Done and checked: multi-stage `Dockerfile` (same Gradle build; JRE runtime, non-root uid 10001, `HEALTHCHECK` on `/actuator/health/readiness`), `compose.yaml` (loopback-only port, mounted admin secret, `/data` volume, read-only root, no capabilities), `.env.example`, HTTPS/proxy/SSE guidance, graceful shutdown (45 s grace). Built and ran the image: reported healthy; `down` then `up` on the same volume kept the administrator (no re-provision); an offline volume copy restored with the new `restore <dir>` command into an empty volume, and the first start logged the epoch rotation (EPOCH changed from the backup's). CI gained an image build and readiness job (not yet run).
- Found and fixed while testing: a `noexec` tmpfs on `/tmp` made sqlite-jdbc fail to load its native library; Compose now mounts `/tmp:exec`.
- Not done: benchmarks (1k/10k/25k, 1% change, excluded trees), the 25,000-file/1 GiB run in Chrome and Edge, the six-platform matrix, the multi-GiB quota run, evidence for all 23 acceptance criteria, and a no-downtime backup command (`BackupService.backup` is library-only). Container replacement was checked for accounts only, not for conflicts, bindings or sync state with real clients.

## Acceptance traceability

Numbers refer to Section 14 of the source specification. Implementation steps identify where work lands; verification steps identify where release evidence is required.

| Criterion | Required outcome | Implementation / verification |
|---|---|---|
| 1 | Two machines exchange additions/edits using supported browsers | 5–6 / 10 |
| 2 | First enrollment never infers deletion | 3, 5 / 8 |
| 3 | Concurrent edits/creation preserve conflicting variants | 3, 6 / 7–8 |
| 4 | Delete-versus-edit conflicts; matching edits converge | 3, 6 / 8 |
| 5 | Stale plans cannot overwrite newer revisions | 4, 6 / 7–8 |
| 6 | Browser/server nested-ignore conformance | 3 / 5, 10 |
| 7 | `.git` is never transferred or deleted | 3, 5 / 8, 10 |
| 8 | Ignore suspension/re-inclusion preserves data and decisions | 3, 5–6 / 8 |
| 9 | Failed scans/permissions/missing baselines cannot cause deletion | 3, 5, 7 / 8 |
| 10 | Interruptions/retries recover without false acknowledgements | 4, 7 / 8 |
| 11 | Interrupted writes/editor races measured on all platforms | 1, 7 / 8, 10 |
| 12 | Collisions, traversal, unauthorized blobs/bindings rejected | 3–5 / 9 |
| 13 | Exact 100 MiB boundary and bounded-memory enforcement | 1, 5, 7 / 10 |
| 14 | Empty directories and blocked directory deletion | 3, 5 / 8, 10 |
| 15 | Current-only storage, authorization, and lease-aware cleanup | 4, 6–7 / 8 |
| 16 | Safe re-enrollment and long-offline tombstone handling | 3, 5–6 / 8 |
| 17 | Restored backup changes epoch and forces reconciliation | 4 / 8, 10 |
| 18 | All starts/resumes are explicit; Scan does not mutate content | 5, 7, 9 / 10 |
| 19 | Immediate unambiguous changes; decisions do not block unrelated work | 5–6 / 8–10 |
| 20 | Owner isolation across every resource and multiple devices | 4–5 / 9 |
| 21 | Source-project byte fidelity and efficient measured scans/transfers | 1, 3, 5, 7 / 10 |
| 22 | Compose persistence and clear startup failures | 4, 10 / 10 |
| 23 | Unlimited default and correct optional cross-space quotas | 7, 9 / 10 |

## Decisions to resolve during implementation

These items refine the implementation; they do not silently change the specification.

- **Step 1:** Streaming hash library, supported browser versions, link detection limitations, staged-write behavior, and measured latency/memory budgets.
- **Steps 2–3:** Exact dependency versions, shared ignore-fixture format, portable path restrictions, and deterministic conflict-name generation.
- **Step 4:** SQLite transaction/journal settings, migration tooling, bounded manifest snapshot/ingestion protocol, plan/idempotency persistence, reference accounting, and restore-epoch handshake.
- **Steps 6–8:** Safe stale-device treatment around tombstones, policy-change rescan flow, confirmation invalidation, and quota treatment of replacement staging with no historical retention.
- **Steps 7–10:** Confirm measured chunk/concurrency defaults, staging/lease expiries, deployment defaults, and performance budgets. The 1 GiB benchmark fixture is not a user storage quota.

Record each decision and its verification evidence alongside the implementation. Any conflict with the specification requires an explicit specification change before release.
