// Browser sync coordinator entry point. Loaded once from the layout shell, outside any HTMX-swapped fragment, so
// in-app navigation can never create a second coordinator. Page load, focus, navigation, network return and timers
// never start a run: only the Scan and Sync now buttons do.
import { Api, ApiError } from "./api/client";
import { acquireCoordinatorSlot } from "./bindings/coordinator-guard";
import {
  LOCK_BUSY, bindingLockName, fileSystemAccessSupported, findOverlap, folderReachable, pickFolder,
  queryWritePermission, requestWritePermission, withBindingLock,
} from "./bindings";
import type { RootHandleLike } from "./fs/types";
import { HashPool, isCancelled, type Hasher } from "./hash/pool";
import { hasPendingRecovery } from "./journal";
import { IdbStore, type BindingRecord, type SyncStore } from "./idb";
import { runSpace, type FileStatus, type RunOutcome, type RunUi, type SpaceInfo } from "./reconcile/run";
import { loadConflicts, loadPreview, resolveConflict, type Resolution } from "./reconcile/conflicts";
import { ChangeWatcher } from "./ui/changes";
import { mountStatus, newPanelState, renderPanel, type PanelState } from "./ui/status";
import { isDeletion, optionLabel } from "./reconcile/conflicts";


class Coordinator {
  private readonly api = new Api();
  private store: SyncStore | null = null;
  private readonly states = new Map<string, PanelState>();
  private readonly active = new Map<string, AbortController>();
  private watcher: { spaceId: string; w: ChangeWatcher } | null = null;

  constructor(private readonly userId: string, private readonly doc: Document) {}

  async db(): Promise<SyncStore> { return (this.store ??= await IdbStore.open(this.userId)); }

  private state(spaceId: string): PanelState {
    let s = this.states.get(spaceId);
    if (!s) { s = newPanelState(); s.supported = fileSystemAccessSupported(); this.states.set(spaceId, s); }
    return s;
  }

  private panel(): HTMLElement | null { return this.doc.getElementById("space-panel"); }
  private panelSpaceId(): string | null { return this.panel()?.dataset["spaceId"] ?? null; }

  private paint(spaceId: string): void {
    const p = this.panel();
    if (p && p.dataset["spaceId"] === spaceId) renderPanel(p, this.state(spaceId));
  }

  /** Called on first load and after every HTMX swap. Reads state only; never starts work. */
  async attach(): Promise<void> {
    const spaceId = this.panelSpaceId();
    if (!spaceId) return;
    const s = this.state(spaceId);
    if (!s.supported) { s.binding = "This browser cannot sync local folders."; this.paint(spaceId); return; }
    s.offline = typeof navigator !== "undefined" && navigator.onLine === false;
    await this.refreshBinding(spaceId);
    this.paint(spaceId);
    await this.refreshConflicts(spaceId);
    await this.watchServer(spaceId);
  }

  /**
   * Shows a note when the server moved past what this page knows. Purely informational: the watcher has no way to start
   * a scan, transfer or resume, so a notification can never cause work.
   */
  private async watchServer(spaceId: string): Promise<void> {
    if (this.watcher?.spaceId === spaceId) return;
    this.watcher?.w.stop();
    this.watcher = null;
    const s = this.state(spaceId);
    try {
      const space = await this.api.get<SpaceInfo>(`/api/spaces/${spaceId}`);
      const w = new ChangeWatcher(`/api/spaces/${spaceId}/events`, space.revision, {
        openStream: (url) => {
          const es = new EventSource(url);
          return {
            close: () => es.close(),
            onRevision: (cb) => es.addEventListener("revision", (ev) => cb((ev as MessageEvent<string>).data)),
            onError: (cb) => { es.onerror = () => cb(); },
          };
        },
        fetchRevision: async () => (await this.api.get<SpaceInfo>(`/api/spaces/${spaceId}`)).revision,
        setTimer: (fn, ms) => window.setTimeout(fn, ms),
        clearTimer: (h) => window.clearTimeout(h as number),
      }, (rev) => {
        s.serverChange = `The server has newer changes (revision ${rev}). Nothing has been applied here: press Scan to preview or Sync now when you are ready.`;
        this.paint(spaceId);
      });
      this.watcher = { spaceId, w };
      w.start();
    } catch { /* signed out or offline: the indicator is optional */ }
  }

  /** Reads the open conflicts (a plain GET: reviewing never starts or resumes a sync). */
  private async refreshConflicts(spaceId: string): Promise<void> {
    const s = this.state(spaceId);
    try {
      const [space, list] = await Promise.all([this.api.get<SpaceInfo>(`/api/spaces/${spaceId}`), loadConflicts(this.api, spaceId)]);
      s.review.deletionEnabled = space.limits.deletionEnabled === true;
      s.review.conflicts = list;
      for (const id of [...s.review.previews.keys()]) if (!list.some((c) => c.id === id)) s.review.previews.delete(id);
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) s.review.note = `Could not load conflicts: ${(e as Error).message}`;
    }
    this.paint(spaceId);
  }

  private async preview(spaceId: string, conflictId: string, side: "local" | "server"): Promise<void> {
    const s = this.state(spaceId);
    const entry = s.review.previews.get(conflictId) ?? {};
    entry[side] = "loading";
    s.review.previews.set(conflictId, entry);
    this.paint(spaceId);
    try { entry[side] = await loadPreview(this.api, conflictId, side); }
    catch (e) { delete entry[side]; s.review.note = `Could not load the preview: ${(e as Error).message}`; }
    this.paint(spaceId);
  }

  /**
   * Applies one explicit decision. It needs this browser's connection to the folder (to write the result) and holds the
   * same cross-tab lock as a run, so a decision can never interleave with a sync of the same folder.
   */
  private async decide(spaceId: string, conflictId: string, resolution: Resolution): Promise<void> {
    const s = this.state(spaceId);
    const c = s.review.conflicts.find((x) => x.id === conflictId);
    if (!c || s.review.busy || this.active.has(spaceId)) return;
    if (resolution === "SKIP") { s.review.note = "Left undecided. Both versions are kept."; this.paint(spaceId); return; }
    const store = await this.db();
    const binding = await store.getBinding(spaceId);
    if (!binding) { s.review.note = "Connect a local folder in this browser to decide conflicts."; this.paint(spaceId); return; }
    let perm = await queryWritePermission(binding.handle).catch(() => "denied" as const);
    if (perm !== "granted") perm = await requestWritePermission(binding.handle).catch(() => "denied" as const);
    if (perm !== "granted") { s.review.note = `Permission required for the folder "${binding.rootName}".`; this.paint(spaceId); return; }
    if (resolution === "KEEP_SERVER" || resolution === "KEEP_LOCAL") {
      const { label } = optionLabel(c, resolution);
      if (isDeletion(c, resolution) && !window.confirm(`${label}: "${c.path}" will be deleted for good. FolderSync keeps no history, trash or restore. Continue?`)) return;
    }
    s.review.busy = true; s.review.note = ""; this.paint(spaceId);
    const ctl = new AbortController();
    this.active.set(spaceId, ctl);
    let hasher: Hasher | null = null;
    try {
      const result = await withBindingLock(bindingLockName(this.userId, spaceId), async () => {
        hasher = new HashPool(2, new URL("hash-worker.js", import.meta.url));
        return resolveConflict({ api: this.api, store, hasher, root: binding.handle, binding, signal: ctl.signal, ui: this.uiFor(spaceId) }, c, resolution);
      });
      s.review.note = result === LOCK_BUSY ? "This folder is being synced in another tab or window. Try again when that run is finished." : result.message;
    } catch (e) {
      s.review.note = isCancelled(e) ? "Cancelled." : `Failed: ${(e as Error).message}`;
    } finally {
      (hasher as Hasher | null)?.terminate();
      this.active.delete(spaceId);
      s.review.busy = false;
    }
    await this.refreshConflicts(spaceId);
  }

  private async refreshBinding(spaceId: string): Promise<void> {
    const s = this.state(spaceId);
    const store = await this.db();
    const b = await store.getBinding(spaceId);
    if (!b) {
      s.connected = false; s.needsReconnect = false; s.needsPermission = false; s.unreachable = false;
      s.binding = "This browser is not connected to a local folder for this space.";
      return;
    }
    s.connected = true;
    if (s.running) return;
    const lastMeta = await store.getMeta(`lastScan:${spaceId}`);
    s.lastScanAt = lastMeta ? Number(lastMeta) : null;
    s.pending = await hasPendingRecovery(store, spaceId).catch(() => false);
    const perm = await queryWritePermission(b.handle).catch(() => "denied" as const);
    const reachable = perm === "granted" ? await folderReachable(b.handle) : true;
    s.needsPermission = perm !== "granted";
    s.unreachable = perm === "granted" && !reachable;
    if (perm !== "granted") {
      s.needsReconnect = true; s.bindingClass = "status-needs-review";
      s.binding = `Permission required for the folder "${b.rootName}". Press Reconnect folder and allow access.`;
    } else if (!reachable) {
      s.needsReconnect = true; s.bindingClass = "status-failed";
      s.binding = `The folder "${b.rootName}" cannot be reached (moved, deleted or disconnected). Reconnect it.`;
    } else {
      s.needsReconnect = false; s.bindingClass = "";
      const last = await store.getMeta(`lastScan:${spaceId}`);
      s.binding = `Connected to "${b.rootName}".` + (last ? ` Last completed run: ${new Date(Number(last)).toLocaleString()}.` : " Not synced yet.");
    }
  }

  // ---- actions (all user-initiated) ----

  async onAction(action: string, spaceId: string, el?: HTMLElement): Promise<void> {
    try {
      switch (action) {
        case "connect": return await this.connect(spaceId, false);
        case "reconnect": return await this.connect(spaceId, true);
        case "disconnect": return await this.disconnect(spaceId);
        case "scan": return await this.run(spaceId, "scan");
        case "sync": return await this.run(spaceId, "sync");
        case "confirm-deletions": return await this.confirmDeletions(spaceId);
        case "cancel": this.active.get(spaceId)?.abort(); return;
        case "preview": return await this.preview(spaceId, el?.dataset["conflict"] ?? "", el?.dataset["side"] === "server" ? "server" : "local");
        case "resolve": return await this.decide(spaceId, el?.dataset["conflict"] ?? "", (el?.dataset["resolution"] ?? "SKIP") as Resolution);
      }
    } catch (e) {
      const s = this.state(spaceId);
      s.running = false;
      if (e instanceof ApiError && e.status === 401) { this.sessionEnded(spaceId); return; }
      if (e instanceof ApiError && e.code === "network_error") s.offline = true;
      s.phase = e instanceof ApiError ? e.message : `Something went wrong: ${(e as Error).message}`;
      this.paint(spaceId);
    }
  }

  private async connect(spaceId: string, reconnect: boolean): Promise<void> {
    const s = this.state(spaceId);
    const store = await this.db();
    const picked = await pickFolder();           // must be the first await: it needs the click's user activation
    if (!picked) return;
    const existing = await store.getBinding(spaceId);

    if (reconnect && existing) {
      if (await picked.isSameEntry(existing.handle).catch(() => false)) {
        await store.putBinding({ ...existing, handle: picked, rootName: picked.name });
        s.phase = "Folder access restored."; await this.refreshBinding(spaceId); this.paint(spaceId); return;
      }
      if (!window.confirm(`"${picked.name}" is not the folder this space was connected to. Connect it as a new folder? Nothing is deleted; both sides are compared afresh.`)) return;
      await this.api.delete(`/api/bindings/${existing.bindingId}`).catch(() => undefined);
      await store.deleteBinding(spaceId);
    }

    const overlap = await findOverlap(picked, await store.allBindings(), spaceId);
    if (overlap) {
      const rel = { same: "is already connected to another space", inside: "lies inside a folder that is already connected", contains: "contains a folder that is already connected" }[overlap.relation];
      s.phase = `This folder ${rel} in this browser. Pick a different folder.`;
      this.paint(spaceId);
      return;
    }

    let deviceId = await store.getDeviceId();
    if (!deviceId) {
      const label = this.panel()?.querySelector<HTMLInputElement>('[data-field="device-label"]')?.value.trim();
      const d = await this.api.post<{ id: string }>("/api/devices", { ...(label ? { label } : {}) });
      deviceId = d.id;
      await store.setDeviceId(deviceId);
    }
    const created = await this.api.post<{ id: string; deviceId: string; enrollmentGeneration: number; epoch: string }>(`/api/spaces/${spaceId}/bindings`, { deviceId });
    // A new binding never inherits baselines: a first join can only add, never delete.
    await store.clearBaselines(spaceId);
    const record: BindingRecord = {
      spaceId, bindingId: created.id, deviceId, handle: picked as RootHandleLike, rootName: picked.name,
      enrollmentGeneration: created.enrollmentGeneration, epoch: created.epoch, connectedAt: Date.now(),
    };
    await store.putBinding(record);
    s.outcome = null; s.files.clear(); s.phase = "Connected. Nothing has been synced yet: press Scan to preview or Sync now.";
    await this.refreshBinding(spaceId);
    this.paint(spaceId);
  }

  private async disconnect(spaceId: string): Promise<void> {
    const store = await this.db();
    const b = await store.getBinding(spaceId);
    if (!b) return;
    if (!window.confirm(`Disconnect this browser from "${b.rootName}"? Files in the folder and on the server are left as they are.`)) return;
    await this.api.delete(`/api/bindings/${b.bindingId}`).catch((e) => { if (!(e instanceof ApiError && e.status === 404)) throw e; });
    await store.deleteBinding(spaceId);
    await store.clearBaselines(spaceId);
    const s = this.state(spaceId);
    s.outcome = null; s.files.clear(); s.phase = "Disconnected.";
    await this.refreshBinding(spaceId);
    this.paint(spaceId);
  }

  /** The person read the listed deletions and pressed the button: a fresh sync that may delete exactly those paths. */
  private async confirmDeletions(spaceId: string): Promise<void> {
    const s = this.state(spaceId);
    const review = s.outcome?.deletions;
    if (!review || !review.executionEnabled || this.active.has(spaceId)) return;
    const paths = new Set([...review.fromServer, ...review.fromThisFolder].map((i) => i.path));
    const n = paths.size;
    const msg = `Delete ${n} item${n === 1 ? "" : "s"} for good?${review.emphasized ? ` This is a large deletion (${review.reason}).` : ""} FolderSync keeps no history, trash or restore.`;
    if (!window.confirm(msg)) return;
    await this.run(spaceId, "sync", paths);
  }

  private async run(spaceId: string, mode: "scan" | "sync", approvedDeletions?: ReadonlySet<string>): Promise<void> {
    if (this.active.has(spaceId)) return;
    const s = this.state(spaceId);
    const store = await this.db();
    const binding = await store.getBinding(spaceId);
    if (!binding) return;

    // Permission is requested here, inside the click's activation window, never in the background.
    let perm = await queryWritePermission(binding.handle).catch(() => "denied" as const);
    if (perm !== "granted") perm = await requestWritePermission(binding.handle).catch(() => "denied" as const);
    if (perm !== "granted") {
      s.needsReconnect = true; s.needsPermission = true; s.bindingClass = "status-needs-review";
      s.binding = `Permission required for the folder "${binding.rootName}".`;
      this.paint(spaceId); return;
    }
    if (!(await folderReachable(binding.handle))) { await this.refreshBinding(spaceId); this.paint(spaceId); return; }

    const ctl = new AbortController();
    this.active.set(spaceId, ctl);
    s.running = true; s.runMode = mode; s.cancelled = false; s.outcome = null; s.files.clear(); s.progress = null; s.phase = mode === "scan" ? "Starting scan" : "Starting sync";
    mountStatus(this.doc, mode === "scan" ? "Scanning..." : "Syncing...");
    this.paint(spaceId);

    let hasher: Hasher | null = null;
    try {
      const result = await withBindingLock(bindingLockName(this.userId, spaceId), async () => {
        hasher = new HashPool(Math.max(2, Math.min(4, navigator.hardwareConcurrency || 2)), new URL("hash-worker.js", import.meta.url));
        return runSpace({ api: this.api, store, hasher, root: binding.handle, binding, signal: ctl.signal, ui: this.uiFor(spaceId), ...(approvedDeletions ? { approvedDeletions } : {}) }, mode);
      });
      if (result === LOCK_BUSY) {
        s.phase = "This folder is being synced in another tab or window of this browser. Close that run first.";
      } else {
        this.finish(spaceId, result, store);
      }
      s.offline = false;
    } catch (e) {
      if (isCancelled(e)) { s.cancelled = true; s.phase = "Paused. Press Resume sync to continue; nothing was lost."; }
      else if (e instanceof ApiError && e.status === 401) { this.sessionEnded(spaceId); }
      else {
        if (e instanceof ApiError && e.code === "network_error") s.offline = true;
        s.phase = `Failed: ${(e as Error).message}`;
        s.outcome = null;
      }
    } finally {
      (hasher as Hasher | null)?.terminate();
      this.active.delete(spaceId);
      s.running = false;
      mountStatus(this.doc);
      await this.refreshBinding(spaceId).catch(() => undefined);
      this.paint(spaceId);
      await this.refreshConflicts(spaceId);
    }
  }

  private finish(spaceId: string, o: RunOutcome, store: SyncStore): void {
    const s = this.state(spaceId);
    s.outcome = o;
    s.phase = ({
      "up-to-date": "Up to date", completed: "Finished", partial: "Finished with errors", "needs-review": "Needs review", paused: "Paused",
      failed: "Failed", cancelled: "Cancelled", preview: "Scan complete", "permission-required": "Permission required",
    } as const)[o.status];
    s.progress = null;
    // A completed pass is what "last scan" means; failed or cancelled runs do not advance it.
    if (o.status === "up-to-date" || o.status === "completed" || o.status === "partial" || o.status === "needs-review" || (o.status === "preview" && o.scannedFiles >= 0)) {
      void store.setMeta(`lastScan:${spaceId}`, String(o.finishedAt));
      s.lastScanAt = o.finishedAt;
    }
    s.serverChange = "";
    void this.api.get<SpaceInfo>(`/api/spaces/${spaceId}`).then((sp) => this.watcher?.w.acknowledge(sp.revision)).catch(() => undefined);
  }

  private uiFor(spaceId: string): RunUi {
    const s = this.state(spaceId);
    let queued = false;
    const repaint = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => { queued = false; this.paint(spaceId); });
    };
    return {
      phase: (t) => { s.phase = t; repaint(); },
      progress: (f) => { s.progress = f ?? null; repaint(); },
      file: (path: string, status: FileStatus, detail?: string) => {
        s.files.delete(path);
        s.files.set(path, { status, ...(detail ? { detail } : {}) });
        repaint();
      },
    };
  }

  /** Signing out ends every run in this page before the session disappears. */
  stopAll(): void {
    for (const c of this.active.values()) c.abort();
    this.watcher?.w.stop();
    this.watcher = null;
  }

  /** The session expired or ended: stop everything in this page. Unfinished work is resumable after signing in again. */
  private sessionEnded(spaceId: string): void {
    this.stopAll();
    const s = this.state(spaceId);
    s.running = false;
    s.cancelled = true;
    s.phase = "Your session has ended. Sign in again, then press Resume sync to continue.";
    this.paint(spaceId);
  }

  setOnline(online: boolean): void {
    for (const [id, s] of this.states) { s.offline = !online; this.paint(id); }
  }
}

const slot = acquireCoordinatorSlot();
if (slot) {
  mountStatus(document);
  const userId = document.querySelector<HTMLMetaElement>('meta[name="foldersync-user"]')?.content ?? "";
  if (userId) {
    const c = new Coordinator(userId, document);
    // Event delegation on the document survives every HTMX swap: buttons inside swapped content just work.
    document.addEventListener("click", (ev) => {
      const target = (ev.target as Element | null)?.closest<HTMLElement>("[data-action]");
      const spaceId = target?.closest<HTMLElement>("#space-panel")?.dataset["spaceId"];
      if (target && spaceId) { ev.preventDefault(); void c.onAction(target.dataset["action"]!, spaceId, target); }
    });
    document.addEventListener("submit", (ev) => { if ((ev.target as Element | null)?.id === "logout-form") c.stopAll(); });
    // Network changes only repaint the status; they never start work.
    window.addEventListener("offline", () => c.setOnline(false));
    window.addEventListener("online", () => c.setOnline(true));
    document.body.addEventListener("htmx:afterSettle", () => void c.attach());
    void c.attach();
  }
}
