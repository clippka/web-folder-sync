# Browser coordinator (Step 5)

How the TypeScript coordinator drives the server contracts in [SERVER.md](SERVER.md). Source: `frontend/src/`.

## Shape

| Module | Role |
|---|---|
| `coordinator.ts` | Single entry point, loaded once from the shell outside the HTMX swap target. Click handlers are delegated on `document`, so swapped content needs no re-binding. Only buttons start work. |
| `bindings/` | Directory picker, permission checks, overlap detection (same, inside, contains) within one browser profile, exclusive cross-tab lock (`navigator.locks`, `ifAvailable`) per user and space. |
| `idb/` | IndexedDB `foldersync:user:<id>` (per origin by construction): folder handle per space, per-path baselines, device id, last-run time. |
| `scan/` | Ignore-aware enumeration, hashing through the worker pool, and `buildPlanInput` (scan + baselines to the manifest). |
| `reconcile/run.ts` | One run: scan, plan, preview stop, stage uploads, commit, apply downloads, adopt, record, acknowledge, rescan after a policy change. |
| `transfer/` | Resumable fixed-size chunk uploads; streamed downloads. |
| `hash/` | Incremental SHA-256, the worker, a bounded pool (2 to 4 workers). Hashing never runs on the page thread, except the running digest over a download stream. |

## Decisions

* **Nothing starts by itself.** There are no focus, visibility, online, timer or SSE listeners. Opening, reloading or navigating a page only reads state. A reload or navigation kills any running run; the user starts another.
* **Scan is a plan that is never committed.** It creates a server plan (metadata only), lists the operations, and releases the plan's lease. No upload, commit, download or local write happens.
* **A baseline path the scan did not observe is "absent" only if its parent was listed and it is gone.** If the local ignore rules skipped it or its directory could not be read, it is sent as `unreadable` (blocked, never a deletion), and baselines that are ignored locally are flagged `suspended` so that re-inclusion is not read as deletion intent (`reincluded`). The server applies its own committed policy regardless.
* **Fresh enrollment is the default for anything unknown.** A new binding clears local baselines. Cleared browser data, a different folder, or a changed server epoch all lead to a join with no baselines, which can add but never delete.
* **Oversized files are not hashed.** The client sends their true size with an all-zero digest; the server marks the path suspended (oversized) before it looks at the digest. The limit comes from `limits` in the space API.
* **Hash reuse** (no-change runs): a stored hash is reused only while file size and `lastModified` are unchanged since it was verified. Anything else is rehashed.
* **Downloads** stream into a `createWritable()` swap file, with the digest computed as bytes arrive. Size or digest mismatch aborts the writable, so the existing file stays and a file created for the download is removed. After `close()` the file is re-read and re-hashed; only then is the baseline written. Immediately before replacing a file the local content is compared with what the plan expected (a differing local edit fails that path, with no overwrite).
* **Uploads** are sliced from the file (one chunk in memory), with bounded retries on network or retryable errors, and are verified by the server over the assembled bytes. After commit, the local file is checked (size and mtime, or a rehash) before the baseline is written.
* **Stale plan, policy or epoch:** the run plans again (up to four passes) instead of retrying or forcing. A control-file change (`.gitignore`/`.syncignore`) triggers a rescan under the new policy.
* **Not in this step:** deletions (proposals are listed as "needs review" and never approved or applied), conflict resolution UI (candidates are uploaded and kept; Step 6), resumable run journals and retries after a restart (Step 7).

## Conflict review and decisions (Step 6)

`reconcile/conflicts.ts` and `ui/review.ts`. The panel lists open conflicts with a plain GET (on page load and after a
run or decision); reading never starts a sync. Each conflict shows both variants (size, short hash with the full hash on
hover, recording device, recorded and last-changed times), an on-demand bounded text preview rendered with `textContent`,
and a download link (server-forced attachment).

* A decision is made from the browser holding the folder and takes the same cross-tab binding lock as a run.
* **Before** anything is recorded the local file is hashed: *Keep server* and *Keep both* need the folder to still hold
  the recorded local variant (or nothing, or the server's content), otherwise the decision is refused so a newer edit is
  never overwritten. *Keep this folder's version* from a folder that did not record the conflict is refused unless the
  content matches.
* The decision is then recorded (version-checked, idempotent), and local files are written with the verified staged
  download used by syncs (digest check, re-read after writing). Baselines are written only for verified bytes. If the
  local write fails, the decision stands, the original file is untouched and the message says to run Sync now.
* *Keep both* downloads the local variant under the server-chosen sibling name first, then replaces the original with
  the server's variant.
* **Deletion review:** proposals from a plan are grouped by direction, with count, an irreversibility note, and emphasis
  for 100 or more files or more than 20% of the folder's files (thresholds come from the server). A normal Sync now never
  deletes; confirmation is described under "Confirmed deletion" below.
* Known limit: if a conflict is still open and the same path is scanned again, the server keeps the first recorded
  candidate (it never discards a variant silently). After a local edit, *Keep this folder's version* uploads the older
  candidate and the newer edit follows on the next sync.

## Interruption and resume (Step 7)

`journal/index.ts`, `reconcile/run.ts`, `transfer/index.ts`. IndexedDB schema version 2 adds two per-user stores: `journal`
(one write-ahead entry per file being replaced) and `uploads` (the begin key of an upload per path).

* **Write-ahead journal.** Immediately before a download replaces a local file, the run stores `{expected, intended}` for
  the path. The per-file baseline is stored right after the verified write and only then is the entry removed. A failure
  before the replacement (aborted write, digest mismatch, local edit detected) removes the entry itself because the folder
  is untouched; a failure after it (post-write re-read mismatch, browser killed) leaves the entry.
* **Settling after an interruption.** At the start of the next explicit Scan or Sync, before any baseline is read, each leftover
  entry is settled from the bytes on disk: intended content means the write finished (baseline recorded from the verified
  hash, no acknowledgement invented); expected content means it never started (old baseline kept); anything else means
  unknown, so the baseline is dropped and the path is planned with no history, which can only adopt identical content or
  keep both variants as a conflict. Nothing is overwritten or deleted. A changed epoch discards the entries with the baselines.
* **Last look before replacing.** After the full content is received and verified, and immediately before `close()` swaps the
  file in, the target is looked up again; a different size or modified time, or a file that appeared, aborts the write. The
  remaining window between that check and the swap cannot be closed with this API (documented editor-race limit).
* **Resumable uploads.** The begin key is stored per path with the content digest and size before the first request. After an
  interruption (cancel, connection loss, closed tab) the next run replays the begin with the same key, asks the upload for
  the chunks it holds, and sends only the missing ones. A record is discarded when the content changed, the upload expired,
  was cancelled or was already consumed by a commit (`consumed` on the upload status), and then a new upload begins. Definite
  server rejections (digest, size, quota, limits) abort the upload and drop the record; connection loss and cancellation keep it.
* **Resume wording.** When a journal entry or upload record exists the button reads *Resume sync*. It is still a click: nothing
  resumes on load, focus, network return or a timer. Retries (three attempts with backoff for network or retryable errors,
  one commit replay with the same idempotency key) happen only inside a run the user started.
* Not done: pausing as a distinct state (Cancel stops the run and leaves the same resumable state), logout shutting the
  coordinator down (Step 9), and the behavior in a real browser killed mid-write (needs the Step 1 hardware runs).

## Confirmed deletion (Step 8)

`reconcile/run.ts` (`approvedDeletions`, `removeLocal`), `ui/review.ts`, `coordinator.ts`.

* The review shows a *Delete these N items for good* button (only when the server's `limits.deletionEnabled` is true). Pressing
  it asks for a browser confirmation and starts a **new explicit sync** with the listed paths as `approvedDeletions`.
* That run scans and plans afresh. A path is approved only if the **new** plan still proposes its deletion; anything else
  that was listed is ignored, so an old approval can never authorize a changed operation. Approvals go to the commit
  (`approvedServerDeletions` / `approvedLocalDeletions`); with the gate closed or the scan incomplete none are sent.
* Server deletions: after the commit applies them, the path's baseline is dropped and acknowledged.
* Local deletions run after the commit, deepest path first. A file is removed only if its hash still equals the reviewed
  content (checked, then looked up again just before `removeEntry`); a folder only through a non-recursive `removeEntry`.
  A changed file, a folder with other content (for example ignored files), a permission error or any other failure keeps the
  path and its baseline, reports "Not deleted: ...", and sends no acknowledgement; the next run proposes it again.
* The window between the last check and `removeEntry` cannot be closed with this API (same editor-race limit as writes).
* There is no trash: `removeEntry` is permanent and FolderSync keeps no copy.

## Verification

* `frontend/test/` (run by `gradlew build`): SHA-256 against `node:crypto`, scanning and pruning against an in-memory folder, plan-input safety rules, and the run loop against a scripted server (first sync, preview, byte-exact downloads, corrupt download, local edit protection, replan, unreadable scans, epoch change, cancellation).
* `frontend/test/recovery.test.mjs`: a crash after the write and its settlement from the disk, never-started and unknown entries, a local edit during the download, upload resume with the same key, stale and content-mismatched upload records.
* `frontend/test/deletion.test.mjs`: unconfirmed, stale and gate-closed approvals, reviewed-bytes checks, children before parents, blocked folders, permission loss, lost acknowledgements, incomplete scans.
* `frontend/test/conflicts.test.mjs`: each decision against an in-memory folder (refusals before recording, failed writes, baselines) and the deletion review thresholds.
* `src/test/.../ConflictResolutionTest.kt`, `ConflictNamesTest.kt`: Keep both, delete-versus-edit rules with the gate closed, previews, unrelated work continuing.
* `src/test/.../SpacePagesTest.kt`: page ownership, escaping, CSRF, limits in the space API.
* `e2e/two-browsers.mjs`: the built jar and two real headless Chrome profiles (see `e2e/README.md`).
