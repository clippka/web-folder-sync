# Server foundation (Step 4)

Contracts and decisions for the persistence, security and storage layers. Source of truth for behaviour is the
[specification](FolderSync_Specification.md); this file records what was built and the decisions the roadmap left open.
Everything below is covered by integration tests under `src/test/kotlin/app/foldersync/`.

## Configuration

All settings are `foldersync.*` properties (environment: `FOLDERSYNC_*`). None is a credential.

| Property | Default | Meaning |
|---|---|---|
| `data-dir` | `./data` | `db/` (SQLite + WAL sidecars), `blobs/`, `staging/`, `EPOCH` |
| `admin.username`, `admin.password-file` | unset | First administrator, created at startup only if no administrator exists. The password is read from the mounted file. Unset and no administrator: nobody can sign in (logged). |
| `max-file-size-bytes` | 104857600 | Complete-file limit; enforced on declared and received bytes |
| `max-included-files` | 25000 | Included files per space (ignored paths and directories do not count) |
| `chunk-size-bytes` | 8388608 | Upload chunk size |
| `max-manifest-batch` | 1000 | Entries per manifest page, plan batch or acknowledgement request |
| `upload-expiry`, `lease-ttl`, `lease-max-lifetime`, `plan-ttl`, `orphan-file-grace`, `cleanup-interval` | 24h, 15m, 2h, 1h, 1h, 5m | Operational expiries |
| `snapshot-max-lag` | 10000 | Revisions a manifest snapshot stays valid |
| `login.max-failures`, `window`, `lockout` | 5, 15m, 15m | Failed-login throttling |
| `deletion-enabled` | `false` | Must stay `false` until the Step 8 safety gate passes (see below) |
| `rotate-epoch` | `false` | Force a reconciliation-epoch rotation at startup |
| `server.servlet.session.cookie.secure` | `true` | Set `false` only for plain-http development |

Startup fails with a message naming the problem when a data directory is not writable, a migration was edited after
being applied, the database is newer than the application, or the administrator secret file cannot be read or is weak.

## Persistence decisions

* **SQLite settings:** WAL journal (sidecars stay in `db/`), `synchronous=FULL`, `busy_timeout=10s`, foreign keys on,
  and `transaction_mode=IMMEDIATE` so a write transaction takes the lock up front and never fails on a read-to-write
  upgrade. Pool of 4. Transactions wrap metadata only; no file transfer or hashing happens inside one.
* **Migrations:** forward-only `db/migration/V<n>__name.sql`, applied by an in-house runner (`Migrator`) in one
  transaction each, checksummed in `schema_migrations`. No downgrade support. A tampered or unknown version stops startup.
* **Serialization:** commits and conflict decisions take a per-space lock in addition to SQLite's single writer.
* **Entry versions:** each path has a monotonically increasing `version` that survives deletion (tombstones are
  compact rows with no blob, hash or size, kept indefinitely). Every server mutation in a plan is guarded by the
  version recorded at planning time.

## Plans, commits and idempotency

1. `POST /api/spaces/{id}/plans` creates a plan in state `INGESTING` (epoch, binding and policy revision are checked).
   `POST /api/plans/{id}/entries` appends batches of at most `max-manifest-batch`; `final: true` (also accepted on
   create) finalizes. Finalizing reads server state, runs the pure `Planner`, and stores the **immutable** operations.
   Paths the client did not mention but the server holds are planned with no baseline and an empty local state, so they
   can only become downloads, never deletions. Invalid and colliding names are listed in `rejected` and excluded.
   The server computes ignore and oversize suspension itself.
2. `GET /api/plans/{id}/operations?after=&limit=` pages through the operations (parents before children).
3. `POST /api/plans/{id}/commit` with an `Idempotency-Key` header applies the server side atomically. Preconditions:
   epoch, policy revision, and the per-path entry version for every operation applied. The space revision may have
   advanced; that alone is not a conflict because the entry guards and parent checks decide whether the change is still
   safe. Any stale guard fails the whole commit with `409 stale_plan` (`details.replan = true`).
   Missing uploads or conflict candidates skip only that operation (`skipped[]`); invalid references fail the commit.
4. `POST /api/plans/{id}/acknowledgements` records completed operations. Uploads must match the committed entry,
   downloads and adoptions must name the planned content.

Idempotency outcomes are stored in the **same transaction** as the work (`idempotency_keys`), so a lost response is
replayed, a different key against a committed plan is `plan_already_committed`, and a reused key with a different body
is `idempotency_key_reuse`. Uploads use state instead of keys for chunks and completion; `begin` takes a key.

## Blobs, references and leases

* Blobs are `data/blobs/<xx>/<yy>/<id>`, named by a random 128-bit id, never by user paths, outside the web root.
* **Reference accounting is derived**, not counted. A blob is *readable* through a current entry, an unresolved conflict
  candidate, or an unexpired lease; it is *retained* additionally while its upload is complete but not yet consumed by a
  commit. Replacing or deleting an entry, resolving a conflict, or letting a lease lapse therefore makes the content
  unreadable immediately (`410 content_unavailable`), before any bytes are removed.
* **Leases** protect the blobs of a plan's download operations: one lease per plan, `lease-ttl` long, renewable up to
  `lease-max-lifetime` after creation (`POST /api/leases/{id}/renew`). After expiry the client replans.
* **Cleanup** (`MaintenanceService`, every `cleanup-interval`, also callable directly): expire uploads and plans,
  release quota reservations, drop lapsed leases, delete unreferenced blobs (row deleted in a transaction that
  re-checks every reference, then the file), and sweep files with no row only after `orphan-file-grace`.
* **Publication order:** chunks are written at their offset into one staging file; the assembled file is hashed once,
  verified against the declared size and SHA-256, moved atomically into the blob store, and only then referenced by a
  database row. The blob id equals the upload id, so a retried `complete` recovers a blob whose move succeeded but
  whose row did not.

## Resuming after interruption (Step 7)

Upload status (`GET /api/uploads/{id}`) is the source of truth for a resuming client: `state`, `receivedChunks` and
`consumed`. A replayed `begin` with the same `Idempotency-Key` returns the original answer (same upload id, but its chunk list is
the one from creation), so the client must read the status. `consumed: true` means a commit already used the upload and a new
one must begin. Expired or cancelled uploads answer `410 upload_expired`. Identical duplicate chunks succeed, inconsistent ones
are `409 chunk_conflict`, and concurrent senders of the same chunk resolve to one stored copy. A completed digest or size
failure releases the quota reservation and the staging bytes at once. Covered by `TransferRecoveryTest`.

## Quotas

Usage is derived across all of an owner's spaces: current file entries, unresolved candidates not already counted as an
entry, and bytes reserved for staged uploads. Reservation happens in the transaction that begins the upload, so
concurrent uploads in different spaces cannot jointly exceed the limit. Reservations convert on commit and release on
cancel, failure or expiry. Quota failure is `422 quota_exceeded`; low disk space is `507 insufficient_disk_space`.
Lowering a quota never touches data and never blocks downloads. A replacement upload counts new and old content
together until the commit publishes it (no history is retained, so this is only transient).

## Manifests and change feed

`GET /api/spaces/{id}/manifest?revision=&after=&limit=` returns a snapshot at a revision, reconstructed from the compact
`entry_changes` log (latest change per path at or below the revision), keyset-paginated by path. The first page reports
the revision to pass on the following pages. Snapshots carry metadata only: a `blobId` appears solely while that state is
still current, and content for a plan is protected by the plan's lease. A snapshot more than `snapshot-max-lag`
revisions old returns `410 snapshot_expired`; a commit whose entries moved returns `stale_plan`. Either way the client
replans from a fresh snapshot, and no historical bytes are ever kept.
`GET /api/spaces/{id}/changes?after=<revision>[&afterPath=]` returns changes and tombstones strictly after the revision,
keyset-paginated; a revision ahead of the server is `409 revision_ahead` (recovery required).

## Conflicts and decisions (Step 6)

A conflict row keeps both variants durably: the server variant by its entry (or, once it moves on, by the row) and the
local variant by the staged candidate upload. `GET /api/spaces/{id}/conflicts` and `GET /api/conflicts/{id}` return,
besides sizes, hashes and the recording device, `serverUpdatedAt`, `bindingId` and `options`, the decisions the
conflict accepts. `POST /api/conflicts/{id}/resolve` takes `{resolution, expectedServerVersion}` and an `Idempotency-Key`.

| Decision | Effect on the server |
|---|---|
| `KEEP_LOCAL` | The candidate becomes the current entry; the old server blob becomes unreadable at once. |
| `KEEP_SERVER` | The entry is unchanged; the candidate is released. |
| `KEEP_BOTH` | The server variant stays at the path. The candidate becomes a new entry at a deterministic sibling name (`name (conflict <device> <yyyy-MM-dd>[ n]).ext`, same directory, valid portable path, unique case- and Unicode-insensitively). Only offered when both sides are files and the path is not an ignore control file. A name that is invalid, ignored by the space policy, or over the file limit rolls back and leaves the conflict unresolved. |
| `SKIP` | Nothing; both variants stay protected. |

* **Delete-versus-edit** is presented as *Keep modified file* or *Confirm deletion*; which stored resolution each maps to
  depends on which side deleted (the browser derives the wording, the server only knows `KEEP_LOCAL`/`KEEP_SERVER`).
  Every resolution that removes a file (`KEEP_LOCAL` with an absent local side, `KEEP_SERVER` with an absent server
  side) answers `409 deletion_disabled` while the gate is closed, and the conflict stays unresolved.
* A decision whose option is not in `options` is `422 resolution_not_available`; a changed file is `409 stale_conflict`.
* `GET /api/conflicts/{id}/preview?side=local|server` returns at most `foldersync.preview-bytes` (16 KiB) of a variant as
  `{kind: text|binary|empty|unavailable, text, truncated, size, sha256}`. Anything with a NUL byte, invalid UTF-8 or
  control characters is `binary` and never decoded. The page writes `text` with `textContent`. Full content is only ever
  served by the blob endpoint, always as an attachment with `nosniff`.
* Review thresholds for large deletions are `foldersync.deletion-warn-count` (100) and `foldersync.deletion-warn-percent`
  (20); they, and `deletionEnabled`, are part of `limits` in the space API.

## Epoch, backup and restore

The **reconciliation epoch** identifies one lineage of server state. It is stored in `server_meta` and mirrored in
`data/EPOCH`. Clients send it when planning and committing (and optionally when reading); a mismatch is
`409 epoch_mismatch` with `details.recovery = true`. The epoch rotates when an operator sets `rotate-epoch=true`, when
the database and sidecar disagree, or when the sidecar is missing. Backups deliberately exclude the sidecar, so a
restored volume always rotates on first start.

Backup (application running): `BackupService.backup(dir)` pauses blob collection, takes a consistent `VACUUM INTO`
snapshot, and copies every blob that snapshot lists. Restore (application stopped, empty data directory): copy `db/` and
`blobs/` from the backup, start the server; it rotates the epoch before serving. Clients then see `epoch_mismatch` or
`revision_ahead` and run recovery reconciliation instead of trusting rolled-back baselines.

## Security

* Spring Security form login, server-side sessions (`FSSESSION`, HttpOnly, Secure, SameSite=Lax, 12 h), session ID
  changed at login, CSRF token in a script-readable cookie echoed as `X-XSRF-TOKEN` (every state-changing request),
  login throttling per username and per address, bcrypt via the delegating encoder, 12+ character passwords.
* Sessions end when the account is disabled or the password changes (`auth_version`).
* `/api/**` answers JSON errors (`unauthenticated` 401, `forbidden`/`csrf_invalid` 403), never the login page.
* Administrators manage accounts, quotas and passwords only. Every space, device, binding, plan, upload, lease, blob and
  conflict query is filtered by the caller's user id; another user's resource answers `404 not_found`.
* Downloads are attachments of `application/octet-stream` with `nosniff` and `no-store`; a blob identifier never grants
  access by itself.
* The audit log records login, space, device, binding, run, commit, conflict decision, candidate discard, deletion,
  quota and epoch events as metadata. Paths are accepted only if they are valid portable relative paths.

## Deletion (enabled since Step 8)

`foldersync.deletion-enabled` defaults to `true`. Setting it to `false` is an operator kill switch: the server then refuses
every deletion approval (`409 deletion_disabled`), including conflict decisions that would delete. With it on, a deletion
needs all of: a complete scan with a baseline for the path (the planner never proposes otherwise), a plan operation that
proposes it, an explicit approval naming that operation at commit, and an unchanged entry version at commit time (a changed
entry is `stale_plan`, a changed epoch `epoch_mismatch`, a committed plan `plan_already_committed`). Directories are removed
only by their own approved operation and never while they hold live children. Evidence: [SAFETY_GATE.md](SAFETY_GATE.md).

## Error codes

JSON body: `{code, message, retryable, path?, details?}`.

| Status | Codes |
|---|---|
| 400 | `malformed_request`, `idempotency_key_required` |
| 401 / 403 | `unauthenticated`; `forbidden`, `csrf_invalid`, `wrong_password` |
| 404 | `not_found` (also for resources owned by someone else) |
| 409 | `stale_plan`, `stale_policy`, `stale_conflict`, `epoch_mismatch`, `revision_ahead`, `plan_already_committed`, `plan_not_open`, `already_resolved`, `chunk_conflict`, `upload_incomplete`, `idempotency_key_reuse`, `deletion_disabled`, `binding_inactive`, `device_revoked`, `server_busy` |
| 410 | `content_unavailable`, `lease_expired`, `upload_expired`, `plan_expired`, `snapshot_expired` |
| 413 / 422 | `file_too_large`, `batch_too_large`, `plan_too_large`, `chunk_size_mismatch`; `quota_exceeded`, `file_limit_exceeded`, `invalid_path`, `path_ignored`, `digest_mismatch`, `size_mismatch`, `unknown_operation`, `upload_mismatch`, `weak_password` |
| 429 / 507 / 503 | `too_many_attempts`; `insufficient_disk_space`; `storage_unavailable` |

`details.replan = true` means: fetch current state and plan again. `details.recovery = true` means: run recovery
reconciliation (epoch change or rolled-back server).

## Product workflows (Step 9)

Server-rendered Thymeleaf pages (plain forms, CSRF token from the form, no inline script) and JSON twins:

| Page | Purpose |
|---|---|
| `/account`, `POST /account/password` | Usage, quota, limits, privacy note; a password change ends every session of the user |
| `/devices`, `POST /devices/{id}/revoke` | Owner's devices; revoking also revokes their bindings |
| `/admin/users` (+ `POST` create, `/{id}/password`, `/{id}/quota`, `/{id}/enabled`) | Administrator only; accounts, quotas (GiB, empty = none) and resets. No space content |
| `/spaces`, `/spaces/{id}` | Connected devices, file and conflict counts, last server change |
| `/spaces/{id}/rules`, `POST /spaces/{id}/rules/preview` | Rule files, matched-rule explanation, policy-change preview |
| `/spaces/{id}/activity` | Metadata-only audit entries of the space |
| `/help` | Public explanation of manual sync, current-only storage, server-readable content, limits |

JSON: `GET /api/spaces/{id}/summary|activity|policy`, `GET /api/spaces/{id}/policy/explain?path=&directory=`,
`POST /api/spaces/{id}/policy/preview {path, text}` (computes only), `GET /api/account/usage`. Every one resolves the space
through the caller's user id, so other users and administrators receive `404 not_found`. A page action that fails with a stable
API error redirects back with the message as a flash notice instead of showing JSON.

## Not in this step

The container image and Compose file (Step 10).
