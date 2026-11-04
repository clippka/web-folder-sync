# Deployment runbook

Operators need Docker Compose and HTTPS infrastructure. No Java, Node.js, Gradle or database service is installed on the host.

## Start

```
cp .env.example .env
mkdir secrets && openssl rand -base64 24 > secrets/admin_password.txt     # 12+ characters; mounted as a Compose secret
docker compose up -d --build
docker compose ps        # STATUS becomes "healthy" (readiness probe /actuator/health/readiness)
```

* The secret file must be readable by uid 10001 (mode 644, or 640 with a matching group); mode 600 owned by you is not.
* The first start creates the administrator named by `FOLDERSYNC_ADMIN_USERNAME` from the mounted file. There are no default
  credentials and no public sign-up; the admin creates other accounts. Later starts do not change the password.
* The image runs as uid 10001 with a read-only root filesystem, no capabilities and `no-new-privileges`. `/tmp` is a tmpfs
  mounted **with exec**, because sqlite-jdbc unpacks its native library there (a `noexec` tmpfs fails at startup).
* `/data` (volume `foldersync-data`) holds `db/` (SQLite + WAL), `blobs/`, `staging/` and the `EPOCH` sidecar.
* The app port is published on `127.0.0.1` only. Put the reverse proxy on the same host or private network.
* Storage that cannot be created or written, a missing or too-short admin secret, or a failed migration stops startup with
  a clear log line (`docker compose logs app`); the container then restarts until fixed.

## Configuration

Any `foldersync.*` property can be set as an environment variable (`FOLDERSYNC_MAX_FILE_SIZE_BYTES`, `FOLDERSYNC_DELETION_ENABLED`,
`FOLDERSYNC_LEASE_TTL`, ...); see `FolderSyncProperties.kt` and [SERVER.md](SERVER.md). Per-user quotas are set by the administrator
in the UI; the default is none.

## HTTPS reverse proxy

The app must be reached over HTTPS: session cookies are `Secure`. The app trusts `X-Forwarded-*` headers
(`server.forward-headers-strategy=framework`), so **only the proxy may reach the app port**. Requirements for any proxy:

* Pass `Host`, `X-Forwarded-For`, `X-Forwarded-Proto: https`.
* Request body limit at least the chunk size (8 MiB) plus overhead; 16m is safe. Whole files are never one request.
* Read/send timeouts of at least 120 s for uploads and downloads.
* **SSE** (`/api/.../events`): disable response buffering and use a read timeout above 60 s, otherwise the change indicator
  falls back to its 60 s polling. Sync never depends on SSE.

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

Only one application instance may use a data volume. Do not scale the service.

## Stop, upgrade, logs

* `docker compose stop` / `down` sends SIGTERM; the app drains in-flight requests (up to 30 s, grace period 45 s), so no new
  commits start and resumable upload state stays on disk. `down` keeps the volume. Only `docker compose down -v` or
  `docker volume rm` deletes data: that is a separate, destructive step.
* Upgrade: set `FOLDERSYNC_VERSION`, `docker compose up -d --build`. Migrations run at startup and are forward-only; there is
  no schema downgrade, so take a backup first and roll back by restoring it.
* Logs: `docker compose logs -f app` (metadata only; no file contents, passwords or absolute client paths).

## Backup and restore

Consistent offline backup (brief downtime; the volume is copied while the app is stopped):

```
docker compose stop app
docker run --rm -v foldersync_foldersync-data:/data:ro -v "$PWD/backup":/backup eclipse-temurin:21-jre cp -a /data/. /backup/
docker compose start app
```

(The volume name is `<project>_foldersync-data`; `docker volume ls` shows it.) Restore into a new, empty volume:

```
docker compose down
docker volume create foldersync_restored
docker run --rm -v foldersync_restored:/data -v "$PWD/backup":/backup:ro foldersync:<version> restore /backup
# point compose at the restored volume (rename the volume or edit compose.yaml), then
docker compose up -d
```

`restore` refuses a non-empty target and copies only `db/` and `blobs/`, never the `EPOCH` sidecar. The first start therefore
logs `Reconciliation epoch rotated (epoch sidecar missing (restored data volume))`, and every client runs recovery
reconciliation instead of trusting baselines from after the backup. Tested against the built image (see
ROADMAP_MVP1.md Step 10 progress). For a backup that needs no downtime, `BackupService.backup` (a `VACUUM INTO` snapshot plus
referenced blobs) exists as a library call but has no operator command yet.
