# Step 8 safety gate: evidence

Deletion is enabled by default (`foldersync.deletion-enabled=true`; `false` is the operator kill switch). This file records
what each gate requirement is verified by and what is still unproven. Run everything with `gradlew build` and
`node e2e/two-browsers.mjs`.

| Requirement | Evidence |
|---|---|
| No deletion from first enrollment, incomplete or unreadable scans, missing baselines | `SafetyGateTest` (no deletion ops, approving a non-proposed path is `422`), `SyncFlowTest`, `PlannerTest`, `run.test.mjs` (unreadable baselines), `deletion.test.mjs` (incomplete scan approves nothing) |
| Suspended (ignored, oversized) and re-included paths never become deletions | `SyncFlowTest` (newly ignored), `PlannerTest` (re-inclusion), `scan.test.mjs` |
| Every deletion needs explicit approval tied to a displayed, still-valid plan | `DeletionAndTombstoneTest` (approval must name a displayed operation; changed entry is `409`), `SafetyGateTest` (plan_already_committed, epoch change), `deletion.test.mjs` (fresh plan, stale approval ignored), e2e (proposed, then confirmed) |
| Lost responses / acknowledgements | `SafetyGateTest` (commit replay with the same key yields one tombstone revision), `TransferRecoveryTest`, `deletion.test.mjs` (lost acknowledgement) |
| External edits and stale plans | `deletion.test.mjs` (edit after review keeps file and baseline), `DeletionAndTombstoneTest` (live children, delete versus edit), `SafetyGateTest` (stale local proposal) |
| Permission loss, folder not removable | `deletion.test.mjs` (denied `removeEntry` keeps file, baseline, no acknowledgement) |
| Children before parents; nonrecursive; blocked directories kept with baselines | `DeletionAndTombstoneTest`, `deletion.test.mjs` (fake folder rejects non-empty non-recursive removal) |
| Obsolete references released, leases protect active operations, no recovery copies | `StorageLifecycleTest`, `DeletionAndTombstoneTest` (blob unreadable at once, collected later) |
| Backup restore changes the epoch; rolled-back baselines are not trusted | `StorageLifecycleTest` (restore rotates epoch), `SyncFlowTest` (epoch change), `SafetyGateTest` (approval before a restore cannot delete), `run.test.mjs` (client drops baselines on epoch change) |
| No history, trash or restore mechanism | By construction: tombstones hold no content reference; there is no restore endpoint |

## Not proven

* Everything browser-side ran against in-memory folders and OPFS in headless Chrome, never a user-selected folder, Edge,
  Linux or macOS (Step 1 hardware runs are still outstanding).
* The window between the last local check and `removeEntry` is not closed and not yet measured with an injected external
  editor (Step 1 `race` test never executed). The UI and docs describe it as a limit.
* No full-disk or killed-process injection around the commit/acknowledgement boundary (Step 7 limit carried over).
