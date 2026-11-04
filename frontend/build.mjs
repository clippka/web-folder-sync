// Bundles the browser code with esbuild into the directory Gradle packages into the Spring Boot jar.
// Usage: node build.mjs [outDir]   (default: ../build/generated/frontend/static/app)
import { build } from "esbuild";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(process.argv[2] ?? resolve(here, "../build/generated/frontend/static/app"));
mkdirSync(outDir, { recursive: true });

const common = { bundle: true, format: "esm", target: "es2023", sourcemap: true, minify: true, logLevel: "info" };

// The coordinator and the hash worker are separate bundles: the worker runs in its own global scope.
await build({ ...common, entryPoints: { coordinator: resolve(here, "src/coordinator.ts") }, outdir: outDir });
await build({ ...common, entryPoints: { "hash-worker": resolve(here, "src/hash/hash-worker.ts") }, outdir: outDir });

// htmx is served locally; no third-party script origins (CSP).
copyFileSync(resolve(here, "node_modules/htmx.org/dist/htmx.min.js"), resolve(outDir, "htmx.min.js"));

copyFileSync(resolve(here, "src/app.css"), resolve(outDir, "app.css"));
