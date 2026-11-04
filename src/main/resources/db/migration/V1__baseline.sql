-- FolderSync baseline schema. Timestamps are epoch milliseconds. File bytes never live here.

CREATE TABLE server_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE users (
  id                  TEXT PRIMARY KEY,
  username            TEXT NOT NULL UNIQUE,
  password_hash       TEXT NOT NULL,
  role                TEXT NOT NULL CHECK (role IN ('ADMIN','USER')),
  enabled             INTEGER NOT NULL DEFAULT 1,
  quota_bytes         INTEGER CHECK (quota_bytes IS NULL OR quota_bytes >= 0),
  auth_version        INTEGER NOT NULL DEFAULT 1,
  created_at          INTEGER NOT NULL,
  password_changed_at INTEGER NOT NULL
);

CREATE TABLE spaces (
  id              TEXT PRIMARY KEY,
  owner_id        TEXT NOT NULL REFERENCES users(id),
  name            TEXT NOT NULL,
  revision        INTEGER NOT NULL DEFAULT 0,
  policy_revision INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL,
  UNIQUE (owner_id, name)
);

CREATE TABLE devices (
  id         TEXT PRIMARY KEY,
  owner_id   TEXT NOT NULL REFERENCES users(id),
  label      TEXT NOT NULL,
  last_seen  INTEGER,
  revoked    INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX devices_owner ON devices(owner_id);

CREATE TABLE bindings (
  id                    TEXT PRIMARY KEY,
  owner_id              TEXT NOT NULL REFERENCES users(id),
  device_id             TEXT NOT NULL REFERENCES devices(id),
  space_id              TEXT NOT NULL REFERENCES spaces(id),
  status                TEXT NOT NULL CHECK (status IN ('ACTIVE','DISCONNECTED','SUPERSEDED','REVOKED')),
  enrollment_generation INTEGER NOT NULL,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL
);
CREATE UNIQUE INDEX bindings_one_active ON bindings(device_id, space_id) WHERE status = 'ACTIVE';
CREATE INDEX bindings_space ON bindings(space_id);

-- Immutable content. A row says the bytes exist; it does not say anyone may read them (see blob reference rules).
CREATE TABLE blobs (
  id         TEXT PRIMARY KEY,
  space_id   TEXT NOT NULL REFERENCES spaces(id),
  owner_id   TEXT NOT NULL REFERENCES users(id),
  size       INTEGER NOT NULL,
  sha256     TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX blobs_space ON blobs(space_id);

-- Current state per path. Deleted rows are compact tombstones (no blob, no hash) kept indefinitely.
CREATE TABLE entries (
  space_id      TEXT NOT NULL REFERENCES spaces(id),
  path          TEXT NOT NULL,
  type          TEXT NOT NULL CHECK (type IN ('FILE','DIRECTORY')),
  version       INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0,
  blob_id       TEXT REFERENCES blobs(id),
  sha256        TEXT,
  size          INTEGER,
  source_mtime  INTEGER,
  last_revision INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (space_id, path),
  CHECK (deleted = 0 OR (blob_id IS NULL AND sha256 IS NULL AND size IS NULL))
) WITHOUT ROWID;
CREATE INDEX entries_blob ON entries(blob_id) WHERE blob_id IS NOT NULL;

CREATE TABLE revisions (
  space_id      TEXT NOT NULL REFERENCES spaces(id),
  number        INTEGER NOT NULL,
  actor_user_id TEXT NOT NULL REFERENCES users(id),
  binding_id    TEXT REFERENCES bindings(id),
  operation_id  TEXT,
  created_at    INTEGER NOT NULL,
  PRIMARY KEY (space_id, number)
);

-- Metadata-only history of entry changes. Deliberately no blob reference: no historical content survives.
CREATE TABLE entry_changes (
  space_id      TEXT NOT NULL,
  revision      INTEGER NOT NULL,
  path          TEXT NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('CREATE','UPDATE','DELETE')),
  type          TEXT NOT NULL CHECK (type IN ('FILE','DIRECTORY')),
  sha256        TEXT,
  size          INTEGER,
  entry_version INTEGER NOT NULL,
  PRIMARY KEY (space_id, revision, path),
  FOREIGN KEY (space_id, revision) REFERENCES revisions(space_id, number)
) WITHOUT ROWID;
CREATE INDEX entry_changes_path ON entry_changes(space_id, path, revision);

CREATE TABLE uploads (
  id              TEXT PRIMARY KEY,
  owner_id        TEXT NOT NULL REFERENCES users(id),
  space_id        TEXT NOT NULL REFERENCES spaces(id),
  expected_size   INTEGER NOT NULL,
  expected_sha256 TEXT NOT NULL,
  chunk_size      INTEGER NOT NULL,
  chunk_count     INTEGER NOT NULL,
  state           TEXT NOT NULL CHECK (state IN ('OPEN','COMPLETE','FAILED','EXPIRED','ABORTED')),
  failure_code    TEXT,
  blob_id         TEXT REFERENCES blobs(id),
  consumed        INTEGER NOT NULL DEFAULT 0,
  expires_at      INTEGER NOT NULL,
  created_at      INTEGER NOT NULL
);
CREATE INDEX uploads_state ON uploads(state, expires_at);
CREATE INDEX uploads_blob ON uploads(blob_id) WHERE blob_id IS NOT NULL;

CREATE TABLE upload_chunks (
  upload_id TEXT NOT NULL REFERENCES uploads(id) ON DELETE CASCADE,
  idx       INTEGER NOT NULL,
  size      INTEGER NOT NULL,
  sha256    TEXT NOT NULL,
  PRIMARY KEY (upload_id, idx)
) WITHOUT ROWID;

CREATE TABLE sync_runs (
  id              TEXT PRIMARY KEY,
  owner_id        TEXT NOT NULL REFERENCES users(id),
  space_id        TEXT NOT NULL REFERENCES spaces(id),
  binding_id      TEXT NOT NULL REFERENCES bindings(id),
  base_revision   INTEGER NOT NULL,
  policy_revision INTEGER NOT NULL,
  state           TEXT NOT NULL CHECK (state IN ('PLANNING','PLANNED','COMMITTED','FAILED','EXPIRED')),
  planned_ops     INTEGER NOT NULL DEFAULT 0,
  applied_ops     INTEGER NOT NULL DEFAULT 0,
  error_code      TEXT,
  created_at      INTEGER NOT NULL,
  finished_at     INTEGER
);
CREATE INDEX sync_runs_space ON sync_runs(space_id, created_at);

CREATE TABLE plans (
  id              TEXT PRIMARY KEY,
  owner_id        TEXT NOT NULL REFERENCES users(id),
  space_id        TEXT NOT NULL REFERENCES spaces(id),
  binding_id      TEXT NOT NULL REFERENCES bindings(id),
  run_id          TEXT NOT NULL REFERENCES sync_runs(id),
  state           TEXT NOT NULL CHECK (state IN ('INGESTING','READY','COMMITTED','EXPIRED')),
  phase           TEXT CHECK (phase IN ('CONTROL','CONTENT')),
  server_revision INTEGER,
  policy_revision INTEGER NOT NULL,
  epoch           TEXT NOT NULL,
  scan_complete   INTEGER NOT NULL,
  paused          INTEGER NOT NULL DEFAULT 0,
  rescan_required INTEGER NOT NULL DEFAULT 0,
  input_count     INTEGER NOT NULL DEFAULT 0,
  expires_at      INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  committed_at    INTEGER
);
CREATE INDEX plans_space ON plans(space_id, created_at);

-- Bounded manifest ingestion: batches land here and are removed when the plan is finalized.
CREATE TABLE plan_inputs (
  plan_id       TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  path          TEXT NOT NULL,
  baseline_type TEXT NOT NULL,
  baseline_sha  TEXT,
  baseline_size INTEGER,
  unreadable    INTEGER NOT NULL DEFAULT 0,
  local_type    TEXT,
  local_sha     TEXT,
  local_size    INTEGER,
  reincluded    INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (plan_id, path)
) WITHOUT ROWID;

CREATE TABLE plan_rejects (
  plan_id TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  path    TEXT NOT NULL,
  reason  TEXT NOT NULL,
  PRIMARY KEY (plan_id, path)
) WITHOUT ROWID;

-- Immutable once the plan is READY.
CREATE TABLE plan_operations (
  plan_id          TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  seq              INTEGER NOT NULL,
  kind             TEXT NOT NULL,
  path             TEXT NOT NULL,
  needs_decision   INTEGER NOT NULL,
  state_type       TEXT,
  state_sha        TEXT,
  state_size       INTEGER,
  expected_type    TEXT,
  expected_sha     TEXT,
  expected_size    INTEGER,
  expected_version INTEGER,
  reason           TEXT,
  cause            TEXT,
  blob_id          TEXT,
  lease_id         TEXT,
  PRIMARY KEY (plan_id, seq)
) WITHOUT ROWID;
CREATE UNIQUE INDEX plan_operations_path ON plan_operations(plan_id, path);
CREATE INDEX plan_operations_blob ON plan_operations(blob_id) WHERE blob_id IS NOT NULL;

CREATE TABLE plan_acks (
  plan_id     TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  path        TEXT NOT NULL,
  outcome     TEXT NOT NULL CHECK (outcome IN ('APPLIED','FAILED','PENDING')),
  sha256      TEXT,
  recorded_at INTEGER NOT NULL,
  PRIMARY KEY (plan_id, path)
) WITHOUT ROWID;

CREATE TABLE conflicts (
  id                   TEXT PRIMARY KEY,
  owner_id             TEXT NOT NULL REFERENCES users(id),
  space_id             TEXT NOT NULL REFERENCES spaces(id),
  plan_id              TEXT REFERENCES plans(id),
  path                 TEXT NOT NULL,
  kind                 TEXT NOT NULL CHECK (kind IN ('CONTENT','DELETE_VS_EDIT','STRUCTURAL')),
  state                TEXT NOT NULL CHECK (state IN ('UNRESOLVED','RESOLVED')),
  resolution           TEXT,
  binding_id           TEXT REFERENCES bindings(id),
  device_label         TEXT,
  server_entry_version INTEGER NOT NULL,
  local_type           TEXT NOT NULL,
  local_sha            TEXT,
  local_size           INTEGER,
  local_blob_id        TEXT REFERENCES blobs(id),
  server_type          TEXT NOT NULL,
  server_sha           TEXT,
  server_size          INTEGER,
  server_blob_id       TEXT REFERENCES blobs(id),
  created_at           INTEGER NOT NULL,
  resolved_at          INTEGER
);
CREATE UNIQUE INDEX conflicts_one_open ON conflicts(space_id, path) WHERE state = 'UNRESOLVED';
CREATE INDEX conflicts_local_blob ON conflicts(local_blob_id) WHERE local_blob_id IS NOT NULL;
CREATE INDEX conflicts_server_blob ON conflicts(server_blob_id) WHERE server_blob_id IS NOT NULL;

-- Temporary read protection for in-flight operations. A lease with a NULL blob_id covers every blob that the
-- operation (a plan) lists, so one renewal protects a whole run; a lease with a blob_id covers that blob only.
CREATE TABLE transfer_leases (
  id           TEXT PRIMARY KEY,
  owner_id     TEXT NOT NULL REFERENCES users(id),
  space_id     TEXT NOT NULL REFERENCES spaces(id),
  operation_id TEXT NOT NULL,
  blob_id      TEXT REFERENCES blobs(id),
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  released     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX leases_blob ON transfer_leases(blob_id) WHERE blob_id IS NOT NULL;
CREATE INDEX leases_operation ON transfer_leases(operation_id);
CREATE INDEX leases_expiry ON transfer_leases(expires_at);

CREATE TABLE idempotency_keys (
  owner_id    TEXT NOT NULL,
  scope       TEXT NOT NULL,
  key         TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  response    TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (owner_id, scope, key)
) WITHOUT ROWID;

CREATE TABLE quota_reservations (
  id         TEXT PRIMARY KEY,
  owner_id   TEXT NOT NULL REFERENCES users(id),
  space_id   TEXT NOT NULL REFERENCES spaces(id),
  upload_id  TEXT UNIQUE REFERENCES uploads(id),
  bytes      INTEGER NOT NULL CHECK (bytes >= 0),
  state      TEXT NOT NULL CHECK (state IN ('RESERVED','COMMITTED','RELEASED')),
  created_at INTEGER NOT NULL
);
CREATE INDEX quota_owner ON quota_reservations(owner_id, state);

-- Metadata only: never contents, passwords, session credentials or absolute local paths.
CREATE TABLE audit_log (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  ts            INTEGER NOT NULL,
  actor_user_id TEXT,
  action        TEXT NOT NULL,
  space_id      TEXT,
  detail        TEXT
);
CREATE INDEX audit_space ON audit_log(space_id, ts);
