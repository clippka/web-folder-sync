# Build and developer startup

Prerequisites: JDK 21 and Node.js 24+ with npm 11 on the PATH. Gradle is supplied by the checked-in wrapper.

| Task | Windows | Linux/macOS |
|---|---|---|
| Clean build + all tests | `gradlew.bat clean build` | `./gradlew clean build` |
| Run the app | `gradlew.bat bootRun` | `./gradlew bootRun` |
| Frontend only | `cd frontend && npm ci && npm run typecheck && npm run build` | same |

`build` runs, in order: `npm ci` (lockfile-pinned) → `tsc --noEmit` → esbuild bundle into
`build/generated/frontend/static/app` → Kotlin compile → frontend node tests → JVM tests → `bootJar`
(the bundle is packaged under `static/app/`). The app stores data under `foldersync.data-dir`
(default `./data`; SQLite file at `<dir>/db/foldersync.db`).

## Pinned choices (Step 2, 2026-11-04)

| Component | Version | Note |
|---|---|---|
| Gradle | 9.8.2 | wrapper |
| Kotlin | 2.4.21 | `kotlin("jvm")`, `plugin.spring`, JVM toolchain 21 |
| Spring Boot | 4.1.2 | latest GA at selection time (4.2.0 was still RC) |
| sqlite-jdbc | 3.54.1.0 | |
| TypeScript | 7.0.2 | type check only (`noEmit`) |
| esbuild | 0.28.2 | bundles `coordinator.js` and `hash-worker.js` |
| htmx.org | 2.0.11 | copied into the bundle; served locally |
| npm | 11.19.0 | `packageManager` + `package-lock.json` |
| Node.js | 24 | CI and local |

Added in Step 4: `tools.jackson.module:jackson-module-kotlin` (managed by the Boot BOM). Migrations use an in-house runner (see `docs/SERVER.md`). The browser streaming SHA-256 library and the CI results on Windows/Linux are still open (see ROADMAP_MVP1.md).
