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

## Step 4 — Build secure server persistence and ownership boundaries

**Depends on:** Steps 2–3.  
**Specification:** Sections 3, 8–12.

- [ ] Add versioned SQLite migrations for users, spaces, devices, bindings, entries, revisions, metadata-only entry changes, uploads, runs, conflicts, and transfer leases.
- [ ] Add persistence for immutable plans, idempotency outcomes, quota reservations, acknowledgements, and a reconciliation epoch; finalize missing protocol fields before browser integration.
- [ ] Keep database transactions short and separate from content transfer. Serialize commits per space and apply optimistic entry/space/policy preconditions.
- [ ] Implement password hashing, server sessions, fixation protection, secure cookies, CSRF, login rate limiting, logout, password changes, and mounted-secret administrator bootstrap without default credentials or public signup.
- [ ] Implement space/device/binding ownership checks for every nested resource. Administrator privileges must not grant access to another user's sync content.
- [ ] Create generated-ID immutable blob storage outside the web root, staging, reference accounting, renewable bounded operation leases, and cleanup.
- [ ] Authorize downloads through current entries, unresolved candidates, or eligible active operations; reject obsolete-content access immediately even before physical cleanup.
- [ ] Define stable paginated manifest snapshots, bounded manifest ingestion, change feeds, JSON errors, retryability, and all route contracts in specification Section 11. Specify snapshot expiry/replan behavior without retaining historical file bytes.
- [ ] Design and test publication crash boundaries: finalized blob before metadata reference, transactional revisions, orphan cleanup, and restart consistency.
- [ ] Implement metadata-only audit records without absolute local paths, file contents, passwords, or session credentials.
- [ ] Define consistent metadata-plus-referenced-blob backup procedures and a restore workflow that changes the reconciliation epoch before clients reconnect.

**Deliverables:** Secured persistence/services, migrations, API contracts, storage lifecycle, and backup/epoch design.

**Exit gate:** Integration tests reject cross-user access using known resource IDs, including admin attempts, uploads, plans, conflicts, leases, and blobs. Stale commits fail safely. Restart and blob-publication failure tests preserve referenced content and metadata consistency.

## Step 5 — Deliver the first-sync vertical slice

**Depends on:** Steps 1–4.  
**Specification:** Sections 2–4, 6–7, 10–11, 13.

- [ ] Implement login, space creation/list/detail, Connect local folder, optional device labels, Disconnect, and Reconnect.
- [ ] Persist handles and per-path state in IndexedDB, namespaced by server origin and user ID. Reject duplicate/overlapping active bindings where handle APIs permit detection.
- [ ] Treat cleared browser data, unknown identity, or enrollment-generation mismatch as fresh non-destructive enrollment. Explain the separate-profile overlap limitation.
- [ ] Implement full enumeration with explicit missing/ignored/unreadable/failed outcomes, early subtree pruning, directory entries, streaming worker hashing, and bounded concurrency.
- [ ] Implement the control-file phase and server policy validation before ordinary content planning.
- [ ] Implement Scan as preview only and Sync now as an explicit single run. Enforce the browser binding lock and prevent duplicate coordinators/workers after HTMX navigation.
- [ ] Transfer first-sync additions both ways, adopt identical content without transfer, queue same-path differences as conflicts, and propagate nested empty directories.
- [ ] Verify upload hashes server-side and resulting local bytes before persisting successful per-path baselines and acknowledgements.
- [ ] Surface per-file progress, ignored and unsupported paths, oversized files, partial success, permission requirements, cancellation, and connection failures.

**Deliverables:** Working browser-to-server-to-second-browser first sync, including non-mutating preview.

**Exit gate:** Two machines exchange exact bytes and empty directories. Joining empty or partially populated folders never deletes existing data. Page load, connect, focus, network return, notifications, and timers never start sync. Unsupported browsers can use account/server pages but cannot run local sync.

## Step 6 — Add incremental reconciliation and durable conflict review

**Depends on:** Step 5.  
**Specification:** Sections 5–8, 11–14.

- [ ] Integrate acknowledged baselines, entry revisions, change feeds, and indefinitely retained tombstones for later runs and long-offline devices.
- [ ] Execute unambiguous updates immediately within the clicked run. Replan on stale revisions/policies without unconditional overwrite.
- [ ] Preserve both conflicting current variants durably, staging the local candidate when required. Quota/staging failures leave the conflict unresolved and existing files intact.
- [ ] Implement Keep local, Keep server, Keep both, and Skip with expected-version checks; use valid unique sibling names for Keep both.
- [ ] Implement delete-versus-edit review as Keep modified file or Confirm deletion. Keep deletion execution disabled until Step 8 passes.
- [ ] Show size, hash, device, and recorded times; escape and limit text previews and force binary attachment downloads.
- [ ] Ensure conflict decisions release unchosen candidates only after successful resolution. Audit explicit candidate discard and provide no undo/history UI.
- [ ] Build deletion review showing paths, count, direction, and irreversibility; emphasize batches of at least 100 files or more than 20% of active files, with configurable thresholds.
- [ ] Verify conflicts block only dependent paths, except conflicting ignore control files, which pause the affected space.

**Deliverables:** Multi-device updates, durable conflict candidates and resolution UI, tombstones, and deletion proposals.

**Exit gate:** Concurrent edits preserve candidates across browser/server restarts. Matching edits converge. Stale conflict decisions require refresh. Unrelated updates complete while decisions remain pending. Physical retention never enables obsolete-content downloads.

## Step 7 — Implement transfer recovery, operation journals, and quotas

**Depends on:** Steps 5–6.  
**Specification:** Sections 7–8, 10–12.

- [ ] Complete resumable fixed-size chunk uploads, initially targeting 8 MiB chunks and four concurrent transfers, subject to Step 1 measurements.
- [ ] Persist upload IDs, chunk state, size/digest expectations, expiries, and idempotency keys. Identical duplicate chunks succeed; inconsistent duplicates fail; final digest verification precedes publication.
- [ ] Stream authorized downloads under bounded leases and replan expired operations against current state.
- [ ] Write IndexedDB journals before local mutation. Recheck expected local bytes immediately before writes, verify results, and advance baselines only after successful completion.
- [ ] On explicit resume, compare actual content with expected and intended states; recognize completed operations or raise conflicts without false acknowledgements.
- [ ] Handle pause, logout, offline state, revoked permissions/devices, browser shutdown, and server restart. Allow bounded retries only while the user-started run remains active.
- [ ] Reserve quota atomically across an owner's spaces for current files, unresolved candidates, and staged transfers; convert/release reservations safely on publication, cancellation, and expiry.
- [ ] Enforce complete-file size limits using declared and received bytes, included-file count limits, and bounded memory. Continue eligible paths while preserving blocked-path copies/baselines.
- [ ] Distinguish quota exhaustion from physical disk exhaustion. Lowered quotas preserve existing content and allow downloads/confirmed deletions; removing the quota permits uploads again.

**Deliverables:** Resumable transfers, persistent operation recovery, safe retries, lifecycle controls, and quota accounting.

**Exit gate:** Inject lost responses, duplicate requests, interrupted writes, full disk, expired staging, SQLite contention, concurrent uploads, and process termination at commit/acknowledgement boundaries. Recovery neither duplicates commits nor advances unsuccessful baselines. Exact-limit files succeed; files one byte larger and chunk-based limit bypasses fail safely.

## Step 8 — Pass the safety gate and enable confirmed deletion

**Depends on:** Steps 3–7 and their passing recovery/concurrency tests.  
**Specification:** Sections 4–7, 14–15.

- [ ] Run fault-injection coverage for incomplete enumeration, root disconnect, permission loss, stale plans, external edits, lost acknowledgements, suspended paths, and missing baseline state.
- [ ] Verify all server and local deletions require explicit approval tied to a displayed, still-valid plan; stale approvals cannot authorize changed operations.
- [ ] Enable file deletion only after these tests pass; keep expected-content/version checks immediately before mutation.
- [ ] Remove approved children before parents and remove directories nonrecursively only when their own deletion is planned and confirmed.
- [ ] Leave directories with ignored/unexpected children intact, report blocked deletion, and retain pending baseline state. Treat opposite-side new/modified descendants as structural conflicts.
- [ ] Verify obsolete references are released, leases protect active operations, and cleanup preserves current content and unresolved candidates without creating recovery copies.
- [ ] Test backup restoration with an epoch change and mandatory recovery reconciliation; never interpret rolled-back revisions as a valid unchanged baseline.

**Deliverables:** Enabled confirmed deletion, recorded safety-gate evidence, and tested restore reconciliation.

**Exit gate:** No failed/incomplete scan or first enrollment propagates deletion. Deletion and replacement remain safe across tested interruption/concurrency cases, with the measured external-editor race documented. No app-level history, trash, or restore mechanism exists.

## Step 9 — Complete product workflows and security verification

**Depends on:** Steps 5–8.  
**Specification:** Sections 9, 12–14.

- [ ] Complete account/password, administrator account/reset/quota, device/revocation, and limits workflows.
- [ ] Complete space summaries, conflict review, matched-rule explanations, policy-impact preview, activity, and per-file errors with partial-success reporting.
- [ ] Implement every specified status, including Permission required, Needs review, Paused, Offline, and Failed; Up to date must show the last completed scan time.
- [ ] Add SSE change indicators and polling fallback that never scan, transfer, or resume automatically.
- [ ] Explain current-only storage, lack of per-file recovery, server-readable content, manual operation, suspended tabs, unsupported paths, and the concurrent-editor limitation in relevant UI/help.
- [ ] Verify restrictive CSP, locally bundled scripts, escaped filenames/previews, attachment downloads, MIME-sniffing protection, CSRF for every mutation, and JSON API authentication errors.
- [ ] Exercise all routes for owner isolation, path validation, cross-user IndexedDB binding isolation, session expiry, and logout coordinator shutdown.

**Deliverables:** Complete v1 screens, help text, and security regression coverage.

**Exit gate:** End-to-end tests cover the documented journeys without requiring a CLI, extension, installed PWA, or agent. Administrative permissions cannot bypass private-space ownership.

## Step 10 — Package deployment and verify performance and release readiness

**Depends on:** Steps 1–9.  
**Specification:** Sections 8–9, 14–15.

- [ ] Build a versioned multi-stage container image from the same Gradle definition, containing only runtime dependencies and packaged assets, running as a non-root user.
- [ ] Deliver `compose.yaml`, a secret-free configuration example, mounted administrator-secret configuration, and a persistent `/data` volume for database/sidecars, blobs, and staging.
- [ ] Document HTTPS reverse-proxy integration, public origin/trusted proxies, restricted application port, upload/time-out limits, and SSE handling.
- [ ] Add health checks, startup migrations, explicit storage initialization failures, and graceful shutdown that stops new commits and preserves recoverable operation state.
- [ ] Test container replacement and ordinary Compose shutdown against the same volume; preserve accounts, current files, unresolved conflicts, bindings, and sync state.
- [ ] Document startup, configuration, upgrades, logs, separate destructive volume deletion, and optional consistent backups. Test a complete restore with epoch rotation; do not promise schema downgrade compatibility.
- [ ] Benchmark initial, no-change, and 1%-changed runs for 1,000/10,000/25,000 included files, nested ignore policies, and large excluded trees. Run the 25,000-file/1 GiB fixture in both Chrome and Edge and complete the six-platform compatibility matrix.
- [ ] Record enumeration, hashing, planning, transfer request counts, memory, and time to first progress against Step 1 budgets. Verify excluded descendants are not hashed and directory/ignored entries do not consume the file allowance.
- [ ] Verify no-change runs transfer no file content, sparse runs transfer only changed included content, and all fixtures preserve exact bytes/line endings.
- [ ] With sufficient disk space, verify a new user with no quota can store more than 10 GiB across spaces within per-file/per-space limits; test simultaneous cross-space uploads under configured quotas, quota removal, and lowering below usage.
- [ ] Run the complete acceptance matrix below and attach evidence to the release checklist. Publish supported browser versions and measured limits only after passing.

**Deliverables:** Versioned image, Compose configuration, deployment/runbook documentation, benchmark results, and release acceptance report.

**Exit gate:** All 23 specification acceptance criteria pass with recorded evidence. Production operators need Docker Compose and HTTPS infrastructure, without installing Java, Node.js, Gradle, or a database service on the host.

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
