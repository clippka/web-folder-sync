# FolderSync

A self-hosted web application that synchronizes ordinary local folders between multiple machines through a central server.
All you need on the client side is desktop Chrome or Edge: no CLI, browser extension, desktop agent or Git installation.

* **Two-way, manual sync.** Nothing syncs until you click *Sync now*. Opening, reloading or focusing a page never starts work.
* **Built for project folders.** Mostly small files, up to 25,000 included files per space, 100 MiB per file by default.
* **Gitignore-aware.** Honors root and nested `.gitignore` and `.syncignore` files (`.syncignore` wins at the same level). `.git` is always excluded.
* **Safe by construction.** Deletions and conflicts always need an explicit confirmation; a scan or a missing baseline can never delete anything.
* **Current files only.** No version history, trash or restore. Replaced and deleted content is not retained.

> **Status:** MVP 1 is in progress. `gradlew build` and the two-browser end-to-end run are green, but some release gates are
> still open (real-hardware browser runs on all OS/browser combinations, injected editor-race tests). See
> [ROADMAP_MVP1.md](ROADMAP_MVP1.md) and [docs/SAFETY_GATE.md](docs/SAFETY_GATE.md) ("Not proven").

## Contents

* [Features](#features)
* [Architecture](#architecture)
* [Repository layout](#repository-layout)
* [Quick start (development)](#quick-start-development)
* [Deployment](#deployment)
* [Configuration](#configuration)
* [Debug setup](#debug-setup)
* [Testing](#testing)
* [CI](#ci)
* [Documentation index](#documentation-index)

---

## Features

| Area | What it does |
|---|---|
| Sync spaces | Named, private server folders owned by one user; each has its own revision counter and ignore policy. A user can have many spaces and many devices bound to each. |
| Local access | File System Access API (`showDirectoryPicker`): the browser reads and writes the selected folder directly. Handles persist in IndexedDB; *Reconnect* renews permission after a restart. |
| Scan / Sync | *Scan* builds and shows a plan without changing anything. *Sync now* applies unambiguous additions and updates immediately. |
| Ignore rules | gitignore syntax, evaluated identically in Kotlin and TypeScript (shared fixtures in `conformance/ignore-fixtures.json`). A matched-rule explainer and policy-change preview are in the UI. |
| Conflicts | Both variants are kept durably. Decisions: keep this folder's version, keep the server's, keep both (deterministic `name (conflict <device> <date>).ext` sibling), or skip. Bounded, binary-safe text previews. |
| Deletions | Proposed only from a complete scan with a baseline; listed for review with counts and an irreversibility note; applied only by a fresh run that re-plans and approves just the paths still proposed. Operator kill switch: `foldersync.deletion-enabled=false`. |
| Resilience | Chunked, resumable uploads (idempotent begin, only missing chunks are resent); a write-ahead journal settles interrupted downloads from the bytes on disk; stale plans/policies/epochs trigger a re-plan, never a forced overwrite. |
| Accounts | Username/password, administrator-created accounts, per-user byte quotas across all spaces, password reset, device revocation. No public sign-up, no default credentials. |
| Isolation | Every query is filtered by the caller's user id; another user's resource (administrators included) answers `404 not_found`. |
| Ops | Single container, SQLite metadata + filesystem blobs on one volume, graceful shutdown, health probes, offline backup and a `restore` command that rotates the reconciliation epoch. |

Out of scope for v1: sharing between users, background/unattended sync, live collaboration, Git operations, Firefox/Safari
sync, a standalone JAR distribution. The full requirements are in [docs/FolderSync_Specification.md](docs/FolderSync_Specification.md).

## Architecture

```
┌──────────────────────── Browser (Chrome / Edge) ────────────────────────┐
│  Thymeleaf pages + HTMX            TypeScript coordinator (esbuild bundle)│
│  (account, spaces, rules,          ├─ bindings/  folder handles, locks    │
│   activity, devices, admin)        ├─ scan/      ignore-aware enumeration │
│                                    ├─ hash/      SHA-256 worker pool      │
│  IndexedDB (per user):             ├─ reconcile/ plan → commit → apply    │
│  handles, baselines, journal,      ├─ transfer/  chunked up/download      │
│  upload records                    └─ journal/   write-ahead recovery     │
└───────────────┬─────────────────────────────────────────────────────────┘
                │ HTTPS (reverse proxy)  JSON API + SSE change indicator
┌───────────────▼─────────────── Spring Boot server (Kotlin) ──────────────┐
│ identity   login, CSRF, throttling, accounts      policy    ignore, paths │
│ spaces     spaces, devices, bindings, insights    reconciliation  Planner │
│ manifests  snapshots, change feed, events         revisions  commits,epoch│
│ transfers  uploads, leases, quotas                blobs   store, cleanup, │
│ journals   acknowledgements                               backup/restore  │
│ audit      metadata-only audit log                web     Thymeleaf pages │
└───────────────┬──────────────────────────────────────────┬──────────────┘
                │ SQLite (WAL, forward-only migrations)    │ blob files
                └──────────────────── /data volume ────────┘
              db/foldersync.db · blobs/<xx>/<yy>/<id> · staging/ · EPOCH
```

### Key ideas

* **Plan / commit protocol.** A client scans its folder and sends a metadata manifest. The server runs the pure `Planner`
  and stores an **immutable** list of operations (upload, download, adopt, conflict, proposed deletion). The client executes
  transfers, then commits with an `Idempotency-Key`. Every applied operation is guarded by the entry version seen at planning
  time, so a stale plan fails whole with `409 stale_plan` and the client re-plans.
* **Baselines.** Each browser stores, per path, the last reconciled hash and entry version. A path with no baseline can only be
  added or adopted, never deleted. Unreadable, ignored or oversized paths are *suspended*, not treated as deleted.
* **Reconciliation epoch.** Identifies one lineage of server state. It rotates on restore from backup (or on request), forcing
  every client to run recovery reconciliation instead of trusting rolled-back baselines.
* **Blobs by derived references.** Blobs are named by random ids, published atomically after a verified digest, and are readable
  only through a current entry, an open conflict candidate or an unexpired lease. Cleanup is periodic and re-checks references.
* **Server never executes client code paths on files**: downloads are `application/octet-stream` attachments with `nosniff`.
* **Pure core, thin shell.** `Planner`, `PortablePath` and `IgnorePolicy` are pure and exhaustively tested; the same ignore
  semantics run on both sides and are checked against shared fixtures.

### Technology

| Layer | Choice |
|---|---|
| Server | Kotlin 2.4, Spring Boot 4.1 (Web MVC, Security, JDBC, Actuator), JDK 21 |
| Views | Thymeleaf + HTMX 2.0 (served locally, no CDN) |
| Browser logic | TypeScript 7 (type check only) bundled with esbuild; no framework |
| Storage | SQLite (`sqlite-jdbc`) for metadata, filesystem for content |
| Build | Gradle 9 (checked-in wrapper) with Kotlin DSL; Node 24 / npm 11 for the frontend |
| Packaging | Multi-stage Docker image, Docker Compose |

Pinned versions are recorded in [docs/BUILD.md](docs/BUILD.md).

## Repository layout

```
src/main/kotlin/app/foldersync/   server (one package per bounded area, see diagram)
src/main/resources/               application.properties, db/migration, Thymeleaf templates
src/test/kotlin/                  integration + unit tests (JUnit, MockMvc)
frontend/                         TypeScript coordinator, hash worker, node:test suite
conformance/                      shared ignore-rule fixtures (Kotlin and TypeScript both verify them)
e2e/                              two-browser end-to-end run against the built jar
harness/                          standalone browser feasibility harness (Roadmap Step 1)
docs/                             specification, server/client contracts, deployment, safety evidence
Dockerfile, compose.yaml          container image and deployment definition
```

## Quick start (development)

Prerequisites: **JDK 21**, **Node.js 24+** with npm 11 on the `PATH`. Gradle comes from the wrapper; no database service is needed.

```sh
# Linux / macOS                       # Windows
./gradlew build                       gradlew.bat build        # npm ci → tsc → esbuild → compile → all tests → bootJar
```

Create a throw-away administrator password and run the app over plain HTTP:

```sh
mkdir -p secrets && printf 'dev-admin-password-123' > secrets/admin_password.txt   # 12+ characters
export FOLDERSYNC_ADMIN_USERNAME=admin
export FOLDERSYNC_ADMIN_PASSWORD_FILE=./secrets/admin_password.txt
export SERVER_SERVLET_SESSION_COOKIE_SECURE=false      # plain-http development only
./gradlew bootRun
```

PowerShell equivalent:

```powershell
New-Item -ItemType Directory -Force secrets | Out-Null
Set-Content -NoNewline secrets/admin_password.txt 'dev-admin-password-123'
$env:FOLDERSYNC_ADMIN_USERNAME = 'admin'
$env:FOLDERSYNC_ADMIN_PASSWORD_FILE = './secrets/admin_password.txt'
$env:SERVER_SERVLET_SESSION_COOKIE_SECURE = 'false'
.\gradlew.bat bootRun
```

Open <http://localhost:8080>, sign in as `admin`, create a user under **Admin → Users**, create a space and connect a folder.
`localhost` counts as a secure context, so the File System Access API works without TLS. Data is stored under `./data`
(`db/foldersync.db`, `blobs/`, `staging/`); delete that directory to reset your dev instance.

The administrator is created only on the first start when no administrator exists; later starts never change the password.

## Deployment

Production runs as **one container** behind a **TLS-terminating reverse proxy**. The host needs only Docker Compose; no Java,
Node.js, Gradle or database service is installed.

### 1. Start

```sh
cp .env.example .env
mkdir secrets && openssl rand -base64 24 > secrets/admin_password.txt   # 12+ characters; mounted as a Compose secret
chmod 644 secrets/admin_password.txt     # must be readable by uid 10001 (640 with a matching group also works; 600 owned by you does not)
docker compose up -d --build
docker compose ps                         # STATUS becomes "healthy" (readiness probe /actuator/health/readiness)
```

`.env` contains no secrets:

| Variable | Default | Purpose |
|---|---|---|
| `FOLDERSYNC_VERSION` | `dev` | Image tag / `org.opencontainers.image.version` |
| `FOLDERSYNC_PORT` | `8080` | Host port, published on `127.0.0.1` only |
| `FOLDERSYNC_ADMIN_USERNAME` | `admin` | First administrator's name |
| `FOLDERSYNC_ADMIN_PASSWORD_FILE` | `./secrets/admin_password.txt` | Host path of the secret file |
| `FOLDERSYNC_MAX_FILE_SIZE_BYTES` | `104857600` | Per-file limit (100 MiB) |

Hardening already applied in `compose.yaml`: non-root uid 10001, read-only root filesystem, all capabilities dropped,
`no-new-privileges`, and a `/tmp` tmpfs **mounted with `exec`** (sqlite-jdbc unpacks its native library there; `noexec` fails at startup).
All state lives in the `foldersync-data` volume mounted at `/data`.

### 2. HTTPS reverse proxy

The app must be reached over HTTPS because session cookies are `Secure`. It trusts `X-Forwarded-*` headers, so **only the proxy
may reach the app port**. Any proxy must pass `Host`, `X-Forwarded-For` and `X-Forwarded-Proto: https`, allow request bodies of
at least 16 MB (chunks are 8 MiB), use read/send timeouts of 120 s or more, and not buffer SSE (`/api/.../events`).

Caddy:

```
sync.example.com {
    request_body { max_size 16MB }
    reverse_proxy 127.0.0.1:8080 { flush_interval -1 }
}
```

nginx (inside a TLS `server` block):

```
client_max_body_size 16m;
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_read_timeout 300s;
}
```

> **One instance per data volume.** SQLite and the per-space locks assume a single application process. Do not scale the service.

### 3. Operate

| Task | Command / note |
|---|---|
| Logs | `docker compose logs -f app` (metadata only: no file contents, passwords or absolute client paths) |
| Stop | `docker compose stop` / `down`: drains in-flight requests (30 s, grace period 45 s). `down` keeps the volume; only `down -v` or `docker volume rm` deletes data. |
| Upgrade | Set `FOLDERSYNC_VERSION`, `docker compose up -d --build`. Migrations run at startup and are **forward-only**: take a backup first and roll back by restoring it. |
| Startup refuses to run | Unwritable data dir, missing/short admin secret, edited-after-applied migration or a database newer than the app. The log names the cause; the container restarts until fixed. |

### 4. Backup and restore

Offline backup (brief downtime):

```sh
docker compose stop app
docker run --rm -v foldersync_foldersync-data:/data:ro -v "$PWD/backup":/backup eclipse-temurin:21-jre cp -a /data/. /backup/
docker compose start app
```

(The volume name is `<project>_foldersync-data`; check `docker volume ls`.) Restore into a new, empty volume:

```sh
docker compose down
docker volume create foldersync_restored
docker run --rm -v foldersync_restored:/data -v "$PWD/backup":/backup:ro foldersync:<version> restore /backup
# point compose at the restored volume (rename it or edit compose.yaml), then
docker compose up -d
```

`restore` refuses a non-empty target and copies only `db/` and `blobs/`, never the `EPOCH` sidecar. The first start therefore logs
`Reconciliation epoch rotated`, and every client runs recovery reconciliation rather than trusting baselines newer than the backup.
A no-downtime snapshot (`BackupService.backup`, a `VACUUM INTO` plus referenced blobs) exists as a library call but has no operator command yet.

More detail: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Configuration

Every `foldersync.*` property can be set as an environment variable (`foldersync.max-file-size-bytes` →
`FOLDERSYNC_MAX_FILE_SIZE_BYTES`). Defaults live in `src/main/resources/application.properties` and
`config/FolderSyncProperties.kt`. None is a credential.

| Property | Default | Meaning |
|---|---|---|
| `data-dir` | `./data` (`/data` in the image) | Database, blobs, staging, `EPOCH` |
| `admin.username`, `admin.password-file` | unset | First administrator, created only if none exists. Unset and none exists: nobody can sign in (logged) |
| `max-file-size-bytes` | 104857600 | Complete-file limit |
| `max-included-files` | 25000 | Included files per space |
| `chunk-size-bytes` | 8388608 | Upload chunk size |
| `max-manifest-batch` | 1000 | Entries per page / plan batch |
| `upload-expiry`, `lease-ttl`, `lease-max-lifetime`, `plan-ttl`, `orphan-file-grace`, `cleanup-interval` | 24h, 15m, 2h, 1h, 1h, 5m | Operational expiries |
| `login.max-failures`, `window`, `lockout` | 5, 15m, 15m | Failed-login throttling |
| `deletion-enabled` | `true` | `false` refuses every deletion approval (kill switch) |
| `rotate-epoch` | `false` | Force an epoch rotation at the next start |
| `FOLDERSYNC_FORWARD_HEADERS` | `framework` | Forwarded-header strategy behind a proxy |
| `server.servlet.session.cookie.secure` | `true` | `false` only for plain-http development |

The full table and the error-code catalogue are in [docs/SERVER.md](docs/SERVER.md).

## Debug setup

### Server (JVM)

**Attach a debugger to Gradle:**

```sh
./gradlew bootRun --debug-jvm        # suspends on port 5005 until a debugger attaches
```

Then attach (IntelliJ: *Run → Attach to Process*, or a *Remote JVM Debug* configuration on `localhost:5005`; VS Code: a
`java` `attach` launch config with `hostName: localhost, port: 5005`).

**Run from IntelliJ IDEA:** open the project as a Gradle project (JDK 21), then run `FolderSyncApplicationKt` with these
environment variables:

```
FOLDERSYNC_ADMIN_USERNAME=admin
FOLDERSYNC_ADMIN_PASSWORD_FILE=<absolute path to secrets/admin_password.txt>
SERVER_SERVLET_SESSION_COOKIE_SECURE=false
```

Note that running the class directly does not build the frontend bundle; run `./gradlew processResources` first (or use
the Gradle `bootRun` task as a run configuration) so `static/app/coordinator.js` exists.

**Useful switches:**

| Goal | Setting |
|---|---|
| Verbose app logging | `LOGGING_LEVEL_APP_FOLDERSYNC=DEBUG` |
| See SQL/connection issues | `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC=DEBUG` |
| Fresh state | Stop the app and delete `./data` |
| Try the restore epoch logic | Set `FOLDERSYNC_ROTATE_EPOCH=true` for one start |
| Inspect the database | Stop the app (or copy the file), then `sqlite3 data/db/foldersync.db` (WAL sidecars `-wal`/`-shm` sit beside it) |
| Probe health | `curl http://localhost:8080/actuator/health/readiness` (also `/liveness`) |

Health details are intentionally hidden (`show-details=never`); the application log is the source of truth. Error responses
never include messages or stack traces (`server.error.include-*=never`), so look in the log for the cause.

### Debugging a container

```sh
docker compose logs -f app
docker compose exec app sh                  # image has curl; root fs is read-only, /data and /tmp are writable
docker compose run --rm -p 5005:5005 -e JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" app
```

The last form starts a throw-away instance with a JDWP port; attach a remote debugger to `localhost:5005`. (Overriding
`JAVA_TOOL_OPTIONS` drops the image's memory flags; add `-XX:MaxRAMPercentage=70` if that matters.) `compose run` publishes
no ports by default, hence the explicit `-p`. Do not run it while the regular `app` service uses the same volume.

### Frontend (browser)

The coordinator is plain TypeScript bundled by esbuild into `build/generated/frontend/static/app`, packaged into the jar.

```sh
cd frontend
npm ci
npm run typecheck        # tsc --noEmit
npm run build -- ../build/generated/frontend/static/app    # rebuild the bundle while bootRun is running, then hard-reload
npm test                 # node --test "test/*.test.mjs"
```

* Open DevTools on `http://localhost:8080`. Source modules are under `frontend/src/`; the hash worker is a separate bundle (`hash-worker.js`).
* **IndexedDB:** *Application → IndexedDB → `foldersync:user:<id>`* holds the folder handle per space, baselines, the write-ahead `journal` and the `uploads` records. Deleting it makes the next run a fresh enrollment (which can add but never delete).
* **Locks:** *Application → Web Locks* shows the exclusive per-user/space binding lock held by a running sync.
* **Strict CSP:** the pages run without inline script. A blocked resource appears in the console as a CSP violation.
* **Reproducing "closed tab" cases:** start a sync, close the tab, reopen: the button reads *Resume sync* and nothing happens until clicked.
* The native folder picker cannot be automated; tests use an OPFS directory behind a stand-in `showDirectoryPicker`.

### Feasibility harness

`harness/` is a dependency-free page for exercising the real File System Access API (locks, handle persistence, hashing, races) on a given OS/browser:

```sh
cd harness && node server.mjs      # http://127.0.0.1:8443/
npm test                           # SHA-256 and fixture generator tests
```

See [harness/README.md](harness/README.md) for fixtures and result files, which feed [docs/feasibility/](docs/feasibility/).

## Testing

| What | Command |
|---|---|
| Everything (frontend tests run first, then JVM tests) | `./gradlew build` |
| JVM tests only | `./gradlew test` |
| One class / method | `./gradlew test --tests 'app.foldersync.SyncFlowTest'` · `--tests '*SafetyGateTest.some method*'` |
| Frontend unit tests | `cd frontend && npm test` |
| Harness tests | `cd harness && npm test` |
| End-to-end (real jar, two headless Chrome profiles) | `./gradlew bootJar && node e2e/two-browsers.mjs` (`CHROME`, `JAVA`, `PORT` override defaults; needs Node 24) |

The JVM suite covers ownership isolation, restart behaviour, startup failures, large-file boundaries, quotas and limits,
transfer recovery, conflict resolution, deletion and tombstones, the safety gate and the product pages. Evidence mapping
for deletion is in [docs/SAFETY_GATE.md](docs/SAFETY_GATE.md).

## CI

`.github/workflows/ci.yml` runs on every push and pull request:

* **build** (Ubuntu and Windows): JDK 21 + Node 24, `./gradlew build --no-daemon`, then the harness tests.
* **image** (Ubuntu): builds the Docker image, starts it with a generated secret and waits for `/actuator/health/readiness`.

## Documentation index

| Document | Contents |
|---|---|
| [docs/FolderSync_Specification.md](docs/FolderSync_Specification.md) | Product requirements, concepts, journeys, API |
| [docs/SERVER.md](docs/SERVER.md) | Server contracts: config, persistence, plans/commits, blobs, quotas, conflicts, security, error codes |
| [docs/CLIENT.md](docs/CLIENT.md) | Browser coordinator modules and decisions: scan, transfer, journal, conflicts, deletion |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Operator runbook: Compose, proxy, upgrade, backup/restore |
| [docs/BUILD.md](docs/BUILD.md) | Build pipeline and pinned tool versions |
| [docs/SAFETY_GATE.md](docs/SAFETY_GATE.md) | Deletion safety evidence and what is still unproven |
| [docs/feasibility/](docs/feasibility/) | Browser feasibility report, compatibility matrix, performance budgets |
| [ROADMAP_MVP1.md](ROADMAP_MVP1.md) | Step-by-step plan, progress and open gates |
