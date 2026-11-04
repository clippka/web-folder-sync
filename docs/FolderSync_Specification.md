# FolderSync specification

Version: 1.0  
Date: 2026-11-04  
Status: Final v1 specification. Browser validation and performance benchmarks are implementation release gates.

## 1. Goal and scope

Provide a self-hosted web application that synchronizes ordinary local folders between multiple machines through a central server. Users need only a supported browser. No CLI, browser extension, desktop agent, Git installation, or installed PWA is required.

Confirmed requirements:

* Two-way synchronization between a selected local folder and server storage.
* Support up to 25,000 included files per project folder after applying ignore rules.
* Set the configurable maximum file size to 100 MiB (104,857,600 bytes) by default.
* Primary workload: source code and project folders containing mostly small files. Optimize for directory enumeration, ignore filtering, and many small transfers.
* Multiple machines can synchronize with the same server folder.
* Honor existing root and nested `.gitignore` files and `.syncignore` files using gitignore syntax. At the same directory level, `.syncignore` rules can override `.gitignore` rules.
* Synchronize regular file contents and directories, including nested empty directories. Skip symlinks and Unix permission metadata, including executable bits.
* Ignore `.git` directories by default.
* Users sign in with a username and password. Administrators create accounts and reset passwords.
* Users start without a storage quota. Administrators can set, change, or remove a byte limit per user across all of that user’s sync spaces.
* Store current files only. No file version history, trash, recovery copies, or restore feature. Sync metadata and unresolved current conflicts remain necessary.
* Sync spaces are private to their owning user and accessible across that user’s machines. Sharing between users is outside v1.
* Kotlin, Spring Boot, HTMX, and Thymeleaf.
* Build with Gradle and Kotlin DSL, using a checked-in Gradle Wrapper.
* SQLite stores metadata for one application instance. File contents are stored separately on a persistent filesystem volume; no separate database service is required.
* Deploy the server using Docker Compose with persistent storage. A standalone JAR distribution is outside v1.
* Desktop Chrome and Edge are sufficient for v1; Firefox and Safari sync support is not required.
* Synchronization starts only through an explicit user click. No automatic or periodic synchronization.
* Sync now immediately applies unambiguous additions and updates after scanning. Application-level confirmation is required only for conflicts and deletions; a full preview is optional.

V1 scope includes confirmed private folders owned by one user and manual sync. Multiple accounts, conflict handling, deletion confirmation, and interruption recovery form the supporting design. Replaced and deleted content is not retained for user recovery. Sharing between users, background desktop services, live collaborative editing, and Git operations are outside v1.

### Primary workload

Source code and project folders are the confirmed primary workload. Synchronize exact file bytes, including encodings and line endings; do not format source files or normalize CRLF/LF. Project assets and binary files remain supported within the configured limits. This is working-folder synchronization, not Git synchronization: do not execute Git commands, commits, checkouts, or hooks. Each machine retains its own excluded `.git` content.

Prioritize responsive scans of many small files and efficient no-change runs. Apply directory ignore rules before reading or hashing descendants where policy permits pruning. Directories such as `node_modules`, `build`, `target`, and `.gradle` are excluded only when configured in ignore files, not through additional hidden defaults. IDE settings and local configuration follow the same rules.

## 2. Browser feasibility and operating contract

Use the File System Access API to select a directory and request read/write permission. Production requires HTTPS. Selection and permission prompts must originate from a user action. Some directories are restricted by the browser. [1][2]

Confirmed browser scope: desktop Chrome and Edge. Test both browsers on Windows, Linux, and macOS before release. Feature detection and an actual read/write capability check are mandatory. Unsupported browsers can access account and server-folder pages, but cannot run local folder sync. Upload/download fallback is not equivalent to two-way synchronization.

Sync is available while the page is open and executing. Background tabs may be throttled or suspended; closing the browser, suspending the machine, or revoking permission stops synchronization. Do not promise unattended background sync. After interruption, require an explicit Resume sync or Sync now click before reconciliation and transfers continue. Merely reopening or focusing the page must not start synchronization.

Persist folder handles in IndexedDB. Check access on every reconnect; persistent permission may be available but must not be assumed. Provide an explicit Reconnect button when renewed permission is required. [2][3]

HTMX and Thymeleaf render application pages and fragments. A dedicated TypeScript module must implement local scanning, hashing, permissions, transfer orchestration, and local state. These tasks cannot be implemented solely with server-rendered HTML.

## 3. Concepts and ownership

| Concept | Meaning |
|---|---|
| Sync space | Named server folder, owned by one user, with its own synchronization revision counter and policy |
| Local binding | One browser profile's association between a selected directory and a sync space |
| Device | User-visible label such as Work laptop; identifies a browser installation, not trusted hardware |
| Baseline | Last reconciled hash, type and entry version per path; no baseline file bytes |
| Revision | Monotonically increasing server state version within one space |
| Tombstone | Recorded deletion, retained to distinguish deletion from an unknown file |
| Conflict | Incompatible changes requiring the user to select an outcome |

One user may own multiple spaces. Multiple devices belonging to that user may bind to one space. Another user cannot list, read, download, or modify the space, its metadata, content, activity records, conflicts, or transfers. V1 has no invitations, shared folders, or public sharing links. Application administrator status does not grant access to another user’s sync content through the application; server operators can still access underlying storage. A local directory may have only one active binding within a browser profile. Detect overlapping directory selections where handles allow it; reject those bindings. Separate browser profiles cannot be reliably coordinated, so document that users should avoid binding the same local directory through multiple profiles.

The server is the durable rendezvous point. Machines do not connect directly. Server storage is managed by the application; administrators must not edit its backing files as another sync client.

## 4. User journeys

### Create and connect

1. Sign in with username and password.
2. Create a named sync space or select an existing one.
3. Click Connect local folder and grant read/write access.
4. Enter an optional device label.
5. Click Sync now to scan, compare, and apply unambiguous additions and updates immediately. Alternatively, click Scan to inspect an optional preview without applying changes.
6. View per-file progress and upload, download, conflict, ignored-path, and unsupported-path counts.
7. Resolve conflicts and confirm proposed deletions when present. Unrelated unambiguous changes do not wait for those decisions.

### First synchronization

First sync is a non-destructive merge. A path present on only one side is copied to the other. Identical files are adopted without content transfer. Different files at the same path become conflicts. An absent file on a newly connected machine never implies deletion on the server.

### Subsequent synchronization

Sync now starts one scan and reconciliation run and applies unambiguous additions and updates without an intermediate confirmation screen. No timer, page load, folder connection, server event, or visibility change starts a sync run. Scan is a separate explicit action that produces a preview without changing synchronized files. A run processes its planned changes and then returns to idle; subsequent changes require another click. Never start overlapping runs. Hidden or suspended pages show that the requested run may pause.

Within a user-started run, ordinary additions and unambiguous updates apply immediately after validation. Conflicts require a resolution choice and deletions always require confirmation; there is no automatic deletion setting in v1. Users may approve a displayed batch of deletions. Show the affected paths, count, direction, and that deleted content cannot be restored through the app. Deleting at least 100 files or more than 20% of active files receives prominent emphasis in that confirmation. Thresholds are configurable; a first sync never propagates deletion.

### Disconnect and reconnect

Disconnect removes the local association without deleting local or server files. Permission loss pauses operations. Cleared browser data or an unrecognized folder requires a fresh non-destructive enrollment, not reconstruction of deletion intent from absence. Folder names alone do not prove folder identity.

## 5. Synchronization semantics

Compare three states per path: baseline B, current local L, and current server S. For files, compare type and content hash. For directories, compare presence and type at the same relative path. Reconcile children individually. Absence is an explicit state. File timestamps are hints, not conflict-resolution authority.

| Local relative to baseline | Server relative to baseline | Outcome |
|---|---|---|
| Unchanged | Unchanged | No action |
| Changed | Unchanged | Upload local state |
| Unchanged | Changed | Apply server state locally |
| Changed | Changed, identical to local | Adopt shared state |
| Changed | Changed, different from local | Conflict |
| Deleted | Unchanged | Propose server deletion |
| Unchanged | Deleted | Propose local deletion |
| Deleted | Modified | Conflict |
| Modified | Deleted | Conflict |
| Deleted | Deleted | Acknowledge deletion |

The changed-state rows cover additions where B is absent. File/directory collisions and incompatible ancestor/descendant operations are structural conflicts. Block their dependent subtree while allowing unrelated files to synchronize.

Rename is delete plus add in v1. No automatic rename inference. Represent every included subdirectory as a directory entry in the manifest, including one with no included children. Create parents before children. Removing the last file leaves its directory in place. Symlinks, junctions, hardlink identity, permissions, executable bits, ownership, ACLs, and extended attributes are not synchronized in v1. Skip symlinks, including links to targets inside the selected root. Browser behavior for links must be tested; unsupported behavior must be reported rather than silently claimed as preserved.

Local operating-system timestamps need not be preserved. Store source timestamps as informational metadata only. Matching timestamps and size do not prove equal content.

### Conflict resolution

Never resolve conflicts using latest timestamp wins. Display both variants, size, hash, device, and recorded times. Offer Keep local, Keep server, Keep both, and Skip. Text previews are escaped and size-limited; binary files have download actions. Automatic text merge is deferred.

Preserve the conflicting current variants until the user resolves the conflict; do not silently discard either side. These are unresolved candidates, not historical versions. Stage a local candidate on the server when needed so a pending conflict does not rely on the originating browser remaining open. Quota or staging failures leave the conflict unresolved and existing files untouched. Keep both publishes both candidates as current files, using a valid unique sibling path with device label, date, and random suffix. Keep local or Keep server discards the unchosen candidate after successful resolution; there is no undo. Skip retains the pending conflict. Delete-versus-edit conflicts offer Keep modified file or Confirm deletion. Resolve against expected revisions; a newly changed file requires a refreshed decision. Unresolved candidates remain protected until resolved or explicitly discarded and count toward the owner’s storage usage.

## 6. Ignore rules

Confirmed policy: honor root and nested `.gitignore` files and provide root and nested `.syncignore` files for application-specific overrides. Both use Git's pattern syntax: comments, escapes, `*`, `?`, character ranges, `**`, anchored paths, directory patterns, and negation. Within each file the last match wins; patterns are relative to their containing directory. An excluded parent must be re-included before its descendants can be re-included. [4]

Evaluate root-to-leaf; at each level apply `.gitignore` then `.syncignore`. A matching rule at a deeper level takes precedence. Ancestor directory exclusion prevents traversal. Do not read global Git configuration or `.git/info/exclude`.

Mandatory v1 exclusion: any path segment named `.git`, including a `.git` file used by Git worktrees. This cannot be overridden in v1. The stronger exclusion is a proposed safe default beyond the requested directory exclusion.

Ignore policy applies to uploads, downloads, and deletion propagation. Unlike Git's already-tracked-file behavior, newly ignored synchronized files become suspended from sync. Keep their existing copies and baseline metadata. Do not turn ignore changes into delete operations. Removing ignored server copies is a separate explicit action.

On re-inclusion, retained baseline information remains available for conflict detection, but absence accumulated during the ignored period does not automatically authorize deletion. Reconcile these paths conservatively. Apply unambiguous additions and updates within the clicked run; ask only for conflicts and explicitly proposed deletions.

Treat ignore files as synchronized control files wherever their parent is traversable. They cannot exclude themselves, though a whole excluded subtree is not scanned. This deliberate deviation prevents rule files from silently disappearing from policy synchronization.

Before syncing ordinary content, reconcile control-file changes. Conflicting rule files pause the affected space until resolved. Freeze an effective policy revision for a run. Newly included content follows the same manual-run behavior: apply unambiguous additions and updates without extra approval, record the inclusion in the activity view, and prompt only for conflicts or deletions. The optional Scan preview shows the policy impact before synchronization. A policy change invalidates pending plans and triggers a rescan. Server and browser must agree on matcher results through a shared conformance suite; the server enforces its committed policy on all mutations.

The UI explains every ignored path with the matching file and rule. Ignoring a file is not access control and does not remove a current server copy or a pending conflict candidate.

## 7. Safe execution and interruption recovery

1. Obtain an exclusive browser lock for the binding.
2. Check session, folder identity, and read/write permission.
3. Fetch a stable server manifest revision and reconcile ignore control files.
4. Fully enumerate the applicable local tree. Distinguish missing, ignored, unreadable, and failed entries.
5. Hash included files with streaming SHA-256 in a worker. V1 favors full hashing for correctness, with bounded concurrency. Prune excluded directories before content reads, batch metadata work, and keep progress and cancellation responsive during large project scans. Cache optimizations must not silently miss same-size, same-timestamp edits.
6. Submit a manifest and baseline state. The server produces an immutable plan with expected entry versions and a policy revision.
7. Stage unambiguous uploads immediately and queue conflicts and deletions for user decisions. Process unrelated unambiguous paths without waiting for those decisions. Verify size and hash server-side.
8. Commit validated server changes transactionally using revision checks.
9. Apply local operations against expected local content, then verify resulting bytes.
10. Persist successful per-path baselines and acknowledgements. Leave failed and conflicted paths pending.

Do not infer any deletion from an incomplete scan. Permission failures, read failures, or a disconnected root invalidate deletion planning for that run.

Server commits use optimistic concurrency. A stale space revision or changed policy produces a replan response, never an unconditional overwrite. Staging can proceed concurrently across devices; commits are serialized per space. Idempotency keys make retries safe after lost responses.

There is no distributed transaction covering the database and the user's disk. Record a per-path operation journal in IndexedDB before mutation. After an explicit resume click following interruption, compare the actual hash against both expected and intended content and resume or raise a conflict. Bounded retries within a still-active user-started run are allowed; a paused or terminated run never restarts itself. Never advance a baseline merely because a transfer started.

Do not create historical recovery copies before local overwrite or deletion. Recheck the file immediately before mutation and verify after writing. If it no longer matches the planned content, stop that operation and replan or raise a conflict. Use staged writes and abort incomplete writes where supported; do not advance the baseline on failure. Completed replacements and deletions cannot be undone by the app. File System Access does not provide a general compare-and-swap transaction against external editors: a narrow concurrent-write race remains. V1 must clearly advise users to avoid editing a file while it is being replaced. It cannot promise zero data loss under arbitrary simultaneous external writes. A feasibility gate must test this behavior before implementation is considered safe enough to release.

A directory is eligible for removal only when its own deletion is planned and confirmed. Remove approved children before parents and use a nonrecursive removal that requires an empty directory. If ignored or unexpected children remain, leave the directory in place, report its deletion as blocked, and keep its baseline pending. Treat a directory deleted on one side with a new or modified included descendant on the other as a conflict. Full-tree atomic application is not promised.

## 8. Transfer and storage design

Transfer complete changed files rather than binary deltas. Use fixed-size chunks for resumable upload, proposed at 8 MiB, with bounded concurrency. Store upload ID, expected size, chunk state, and final digest. Duplicate chunks with identical bytes are idempotent; inconsistent duplicates fail. Verify the assembled digest before publication.

Downloads stream immutable blobs referenced by current entries, unresolved conflicts, or valid active transfer leases. An interrupted download may restart that file in v1; completed files are not retransferred. Never buffer an entire large file in browser or server memory. Do not require OPFS to hold a complete folder. Verify browser download hashing and writable-stream abort behavior in the prototype.

Store file contents as immutable blobs outside the public web root. Database transactions reference already finalized blobs. Only current entries, unresolved conflict candidates, and active operations may retain content references. A bounded renewable transfer lease protects bytes needed by an in-flight download or commit; an expired operation must replan against current state. After replacement or deletion, release obsolete references and collect blobs once no current reference or valid operational lease remains. Failed or abandoned upload staging is removed after a configured operational expiry. These temporary bytes are not a version archive or a restore mechanism, and the API must not expose obsolete blobs merely because physical cleanup is pending. Physical blob identifiers do not grant access. Every read is authorized through its owning space and an eligible current reference or active operation.

The largest expected project contains 10,001 to 25,000 included files. V1 must support 25,000 files in one sync space after ignore rules are applied. Apply the configured file size limit to the complete file across all chunks. During scanning, flag oversized files by path and size and continue with eligible files. For a blocked path, preserve existing copies and its baseline. Never infer a deletion from the size violation. Enforce the same limit on the server using both declared size and actual bytes received. The proposed defaults for file count and transfer concurrency are 25,000 included files per space and four concurrent transfers. Validate the configured limits during scanning and upload.

Storage quotas are optional. An unset user quota allows uploads subject to available disk space and the file and space limits. When an administrator sets a quota, account for current files, unresolved conflict candidates, and staged transfers across the owner’s spaces. Reserve capacity atomically so concurrent uploads cannot exceed the quota. Convert a staging reservation to committed usage on publication and release it on cancellation or expiry. If a new limit is below current usage, preserve existing data and permit downloads and confirmed deletions. Reject requests that would increase usage until capacity is available. Report insufficient disk space separately from a configured quota violation.

Benchmark source-project fixtures with 1,000, 10,000, and 25,000 included small files, nested ignore files, and large excluded dependency/build trees. The largest expected project contains up to 1 GiB of included file content after applying ignore rules. This figure sizes the performance tests and imposes no storage limit. Run a fixture with 25,000 included files totaling 1 GiB in both desktop Chrome and Edge as an acceptance test. Verify that ignored files and directory entries do not consume the 25,000-file allowance. Measure enumeration, hashing, planning, transfer request count, memory, and time to first progress separately. Include initial sync, a no-change run, and a run changing 1% of files. A no-change run sends no file content; metadata exchange is allowed. A sparse-change run transfers only changed included content. Batch bounded manifest requests and reuse connections to limit per-file overhead without buffering entire folders. Establish numeric latency targets from the browser feasibility results; do not claim performance before measurement.

Keep compact tombstones indefinitely in v1, without retaining deleted file bytes. This prevents stale devices from silently resurrecting deleted paths. Any later tombstone compaction must introduce a minimum supported baseline revision and force safe re-enrollment for older clients.

## 9. Application architecture

| Layer | Proposed implementation |
|---|---|
| Runtime | Kotlin on Java 21; supported Spring Boot release selected at implementation |
| Web UI | Spring MVC, Thymeleaf, HTMX |
| Browser sync | TypeScript modules, Web Workers, File System Access API, IndexedDB |
| Authentication | Spring Security, server-side sessions |
| Metadata | SQLite for one application instance, accessed through Spring JDBC with versioned SQL migrations |
| Content | Managed persistent filesystem volume with immutable blobs |
| Build | Gradle with Kotlin DSL and Gradle Wrapper. Bundle browser TypeScript with esbuild. |
| Updates | Server-Sent Events for server-change indicators; polling fallback. Notifications never trigger local scans or transfers. |
| Delivery | Confirmed: container image and Docker Compose deployment with persistent storage |

SQLite is the confirmed metadata database for v1. File bytes remain outside the database; it holds identities, paths, hashes, revision metadata, tombstones, and operation state. Keep transactions short and never hold a transaction open during file transfer. Multiple application replicas are outside v1. A future multi-instance deployment requires an appropriate database and shared content storage.

Keep the sync coordinator outside HTMX-swapped DOM fragments. Navigating within the app must not accidentally create duplicate workers or lose active synchronization state. Do not use browser local storage for passwords or session tokens.

Server modules: identity, spaces, manifests, ignore policy, reconciliation, transfers, revision metadata, operation journals, blob cleanup, and audit. The reconciliation planner should be a pure Kotlin component independent of HTTP and filesystem I/O.

### Build contract

Use `build.gradle.kts` and `settings.gradle.kts` with a checked-in Gradle Wrapper. The build must compile Kotlin, type-check and bundle the browser TypeScript, run the relevant tests, and package the frontend assets into the Spring Boot application. Keep frontend tooling versions and dependencies pinned through the selected package manager and its lockfile. Gradle runs TypeScript type checking and invokes esbuild to bundle the browser code.

Document a repeatable clean build through the wrapper on Windows and Linux. Container builds use the same build definition and include only runtime dependencies and packaged application assets in the final image. Users running the published Compose deployment do not need Gradle, Node.js, or Java installed on the host.

### Docker Compose deployment

Deliver a versioned container image, `compose.yaml`, a configuration example without secrets, and deployment documentation. Run one application container with its Java runtime included. No separate database container is needed. The Spring Boot JAR is an internal image artifact, not a separately supported v1 deployment.

Proposed deployment layout: a named volume mounted at `/data`, containing the SQLite database under `/data/db`, immutable file content under `/data/blobs`, and temporary transfers under `/data/staging`. Keep database files and any journal sidecars together. Store persistent data outside the container writable layer. Container replacement and ordinary Compose shutdown must preserve users, sync state, files, and unresolved conflicts. Document volume deletion as a separate destructive operation.

Run the application as a non-root user with write access to its data volume. Configure limits, public origin, and trusted proxy settings externally. Provide the initial administrator credential through a mounted secret; do not embed credentials in the image or tracked Compose configuration.

Serve the public app through an HTTPS reverse proxy, with the application port restricted to the proxy or trusted host interface. Document integration with an existing reverse proxy; a bundled proxy is not a confirmed requirement. Configure proxy request limits and timeouts for chunk uploads, streamed downloads, and server events. Trust forwarded headers only from the configured proxy.

Include health checks and graceful shutdown. On shutdown, stop admitting new commits, finish or safely abort bounded in-flight work, and preserve operation metadata for explicit user resume. Run schema migrations at startup before accepting sync traffic; fail startup clearly if migration or storage initialization fails. Document initial startup, configuration changes, upgrades, logs, and optional consistent disaster-recovery backups. Upgrades must preserve the persistent volume; do not promise downgrade compatibility after schema changes.

## 10. Data model

| Entity | Core fields |
|---|---|
| User | ID, normalized username, password hash, role, enabled flag, optional storage quota in bytes (unset by default) |
| Space | ID, owner ID, name, current revision, policy revision |
| Device | ID, owner ID, label, last seen, revoked flag |
| Binding | ID, device ID, space ID, status, enrollment generation |
| Entry | Space ID, relative path, type, entry version, deleted flag. File entries also have a blob reference, hash, and size. Directory entries carry metadata only. |
| Revision | Space ID, number, actor, binding, timestamp, operation ID |
| EntryChange | Entry/path reference, revision, hash/type metadata, change kind; no historical blob reference |
| Upload | ID, owner, space, expected bytes/hash, chunk state, expiry |
| SyncRun | ID, binding, base revision, policy revision, state, counters, errors |
| Conflict | Run/path, baseline version, local variant, server variant, resolution state |
| TransferLease | Operation, owner, space, blob reference, expiry; temporary protection for active I/O |

Browser IndexedDB stores folder handles, per-path acknowledged baselines, suspended-path state, operation journals, and recoverable transfer progress. Namespace records by server origin and user ID. A different logged-in user must never inherit access to another user's bindings. Server acknowledgements aid recovery but cannot substitute for actual local state.

## 11. HTTP contract outline

All routes are same-origin and authenticated except login and minimal public health status. Space ownership is checked for every nested resource.

| Method and route | Purpose |
|---|---|
| GET/POST `/login` | Login page and session creation |
| POST `/logout` | End session and stop local coordinator |
| GET/POST `/api/spaces` | List or create spaces |
| POST `/api/spaces/{id}/bindings` | Enroll a device binding |
| GET `/api/spaces/{id}/manifest` | Stable, paginated manifest snapshot |
| GET `/api/spaces/{id}/changes?after=...` | Changes and tombstones after a revision |
| POST `/api/spaces/{id}/plans` | Validate scan, policy and baseline; create plan |
| POST `/api/spaces/{id}/uploads` | Begin resumable upload |
| PUT `/api/uploads/{id}/chunks/{index}` | Upload one bounded chunk |
| POST `/api/uploads/{id}/complete` | Verify and finalize blob |
| POST `/api/plans/{id}/commit` | Apply approved server operations with revision precondition |
| GET `/api/spaces/{id}/blobs/{blobId}/content` | Download authorized current content, conflict candidate, or content covered by an active transfer lease |
| POST `/api/plans/{id}/acknowledgements` | Record completed local operations |
| POST `/api/conflicts/{id}/resolve` | Resolve against current expected versions |
| GET `/api/spaces/{id}/events` | Revision notification stream |

Use bounded request sizes and paginated manifest ingestion for large trees. Return stable error codes with retryability and affected path. Distinguish authentication failure, permission denial, stale revision, validation failure, expired upload, quota failure, and unavailable storage. JSON endpoints return JSON errors rather than login HTML.

## 12. Security and administration

Use username/password login with Spring Security password hashing, session fixation protection, Secure and HttpOnly cookies, SameSite policy, CSRF protection for all state-changing requests, and login rate limiting. No public signup in v1. Provision the first administrator from a deployment secret; subsequent accounts and password resets are managed by an administrator. Do not ship a default password.

TLS protects transport. The server can read stored content; end-to-end encryption is outside v1. Explain this in the product settings.

Validate canonical relative paths before any filesystem access. Reject absolute paths, traversal, NUL, invalid segments, excessive depth, reserved application names, and platform-incompatible names. Detect case-insensitive and Unicode-equivalent collisions before transfer. Never silently rename or merge colliding paths. Use a conservative portable path policy for v1 and report rejected names explicitly.

Store blobs by generated identity, never by unchecked user paths. Treat uploaded content as untrusted data: force attachment downloads, prevent MIME sniffing, and escape previews and filenames. Use a restrictive Content Security Policy and locally bundled scripts because scripts on this origin can access approved folders.

Audit login, space changes, commits, conflict decisions, deletions, and explicit conflict-candidate discard. Keep activity and change records as metadata only; they must not retain obsolete content. Do not log file contents, passwords, or session credentials. Retain metadata needed for troubleshooting without storing absolute local paths.

## 13. Screens and status

* Login and password change.
* Space list with last sync, bound device, pending changes, and conflict count.
* Space detail with Connect folder, Scan, Sync now, Pause, Resume sync for interrupted runs, and progress. No automatic-sync toggle.
* Optional Scan preview with uploads, downloads, deletions, conflicts, excluded paths, and estimated bytes. Sync now bypasses this preview. Required conflict and deletion decisions remain accessible during and after the run.
* Conflict detail with explicit resolution choices.
* Ignore-rule view with matched-rule explanations and policy-change preview.
* Activity with sync results and errors. No version browser, trash, or restore screen.
* Device management, limits, and administrator user management.

Statuses: Not connected, Permission required, Ready, Scanning, Needs review, Syncing, Up to date, Conflicts, Paused, Offline, and Failed. Show partial success and per-file errors. Up to date always includes the last completed scan time; it must not imply continuous filesystem monitoring.

## 14. Acceptance criteria

1. Two supported desktop browsers on separate machines exchange additions and edits through one space without installing software.
2. Joining an empty or partially populated folder never deletes existing server content.
3. Concurrent edits preserve both variants and produce a conflict, including concurrent creation at the same path.
4. Delete-versus-edit produces a conflict; two matching edits converge without conflict.
5. A stale plan cannot overwrite a newer server revision.
6. Nested ignore rules, escaping and negation match the documented conformance fixtures in browser and server.
7. `.git` content is never uploaded, downloaded, or deleted by synchronization.
8. Newly ignored files remain intact on both sides. Re-inclusion uses retained baselines conservatively; unambiguous additions and updates apply within the clicked run, while conflicts and deletions require a decision.
9. Failed enumeration, lost permission, or missing baseline never becomes a mass deletion.
10. Refresh, process restart, network loss and duplicate requests recover without false acknowledgements or duplicate commits.
11. Interrupted writes and external-editor races are tested on each supported OS/browser combination and documented honestly.
12. Case collisions, path traversal, unauthorized blob access and cross-user binding reuse are rejected.
13. Under the default size setting, an otherwise eligible file of exactly 104,857,600 bytes transfers successfully. A file one byte larger is reported and skipped. An oversized upload cannot bypass the server limit by splitting the file into chunks. Existing copies remain intact, and unrelated eligible files can synchronize. Hashing and transfer remain bounded in memory.
14. Create a nested empty directory on machine A, sync A, then sync B. B acquires the same directory tree without any placeholder files. Repeat in the opposite direction. Removing its last file preserves the directory. Confirming deletion of the directory propagates its removal when empty. If an ignored child remains on the destination, the directory and child survive and the run reports the blocked deletion.
15. Successful replacement or confirmed deletion provides no history or restore action. Obsolete content references are released, unauthorized obsolete-content downloads fail immediately, and physical cleanup occurs after active operation leases end. Current files and unresolved conflict candidates remain intact.
16. Clearing browser data leads to safe enrollment. Long-offline devices cannot silently resurrect tombstoned paths.
17. Restoring a server backup changes its reconciliation epoch so clients cannot mistake rolled-back revisions for current state; clients must perform recovery reconciliation.

18. Page load, folder connection, server events, elapsed time, network reconnection, and page focus never start or restart synchronization. Scan only previews changes; Sync now or Resume sync explicitly starts work.

19. Sync now applies unambiguous additions and updates without a full-plan confirmation, including first sync and newly included paths. Conflicts and deletions require decisions; unrelated changes can complete while those decisions remain pending. Browser permission prompts are separate platform requirements.

20. A second authenticated user cannot list, inspect, download, or mutate another user’s spaces or any associated resources, even with known resource IDs. The owner can synchronize the same space from multiple machines.

21. Source-project fixtures preserve file bytes and line endings, skip excluded dependency/build subtrees without hashing their contents, and remain responsive during scanning. No-change runs transfer no content, and sparse-change runs transfer only changed included content.

22. Recreating the application container with the same Compose volume preserves users, current files, conflict candidates, sync metadata and device bindings. Deployment requires no Java installation or separate database service on the host. Startup fails clearly when storage or schema initialization fails.

23. With sufficient disk space, a new user can exceed 10 GiB in aggregate while respecting the per-file and per-space limits. With a quota configured, simultaneous uploads to different spaces must respect the same user limit. Removing the quota permits uploads again. Lowering it below usage preserves existing files.

## 15. Implementation sequence and release gates

1. Browser feasibility spike: real folder access, reconnect, streaming hash/write, permission loss, large files, locks, links, collisions and write races on the target platforms.
2. Domain core: three-way planner, ignore-policy matcher, path policy and table-driven conflict tests.
3. Server foundation: authentication, ownership, schema, blobs, migrations, consistent backup and restore epoch.
4. First-sync vertical slice: connect, optional scan preview, click to sync, upload, download, verify and acknowledge.
5. Reconciliation: edits, tombstones, concurrent devices, pending conflict candidates and deletion review.
6. Reliability: resumable upload, journals, failure injection, quotas, lifecycle pause and explicit user resume, and automated browser tests.
7. Product completion: activity, rule explanations, manual sync controls, administration, container image, Compose configuration, persistent-volume verification, and deployment documentation.

Do not ship deletion propagation before interruption-handling, concurrency, and fault-injection tests pass. Do not advertise browsers or limits beyond the measured feasibility results. Deployment operators may maintain independent disaster-recovery backups. If configured, these must capture a consistent metadata snapshot plus all referenced immutable blobs; test a complete server restore. Such backups are separate from the app, which offers no per-file history or restore.

## Sources

Browser capabilities were checked on 2026-11-03. Browser support and enterprise policies must be retested during implementation. The acceptance criteria define the checks required before release.

1. MDN, showDirectoryPicker: https://developer.mozilla.org/en-US/docs/Web/API/Window/showDirectoryPicker
2. Chrome Developers, File System Access API: https://developer.chrome.com/docs/capabilities/web-apis/file-system-access
3. Chrome Developers, persistent permissions: https://developer.chrome.com/blog/persistent-permissions-for-the-file-system-access-api
4. Git, gitignore pattern semantics: https://git-scm.com/docs/gitignore
