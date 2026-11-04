# End-to-end run (real server, real bundle, two headless Chrome profiles)

```sh
gradlew bootJar          # builds build/libs/foldersync-*-SNAPSHOT.jar
node e2e/two-browsers.mjs
```

Starts the jar on a temporary data directory, then drives two isolated Chrome profiles over the DevTools protocol:
sign in, create a space, connect, Scan (preview only), Sync now, a second machine joining an empty folder, edits flowing
back, and a no-change run. `CHROME`, `JAVA` and `PORT` override the defaults. Requires Node 24 (global `WebSocket`).

Limit: a native directory picker cannot be automated, so each "local folder" is an OPFS directory returned by a stand-in
for `showDirectoryPicker`. This exercises the coordinator, hash worker, CSP, HTMX navigation and the whole protocol, but it
is **not** evidence about user-selected folders, permission prompts or Edge: that is the Step 1 harness's job.
