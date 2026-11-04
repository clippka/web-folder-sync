// Rendering for the space panel. Everything is written with textContent: file names and rule text come from
// untrusted places (the local disk, other devices) and must never be interpreted as HTML.
import type { FileStatus, RunOutcome } from "../reconcile/run";
import { deriveStatus } from "./state";
import { newReviewState, renderConflicts, renderDeletionReview, type ReviewState } from "./review";

export interface PanelState {
  /** Status line for the binding: "Up to date (last scan ...)", "Permission required", ... */
  binding: string;
  bindingClass?: string;
  connected: boolean;
  needsReconnect: boolean;
  running: boolean;
  phase: string;
  progress: number | null;
  files: Map<string, { status: FileStatus; detail?: string }>;
  outcome: RunOutcome | null;
  supported: boolean;
  /** A previous run left unfinished work (journal entries or staged uploads): the next sync is a resume. */
  pending?: boolean;
  review: ReviewState;
  runMode?: "scan" | "sync" | undefined;
  needsPermission: boolean;
  unreachable: boolean;
  offline: boolean;
  /** The last run was cancelled by the person. */
  cancelled: boolean;
  lastScanAt: number | null;
  /** Set when the server reports a revision this page has not seen. Informational only. */
  serverChange: string;
}

export function newPanelState(): PanelState {
  return { binding: "", connected: false, needsReconnect: false, running: false, phase: "", progress: null, files: new Map(), outcome: null, supported: true, review: newReviewState(), needsPermission: false, unreachable: false, offline: false, cancelled: false, lastScanAt: null, serverChange: "" };
}

const MAX_FILE_LINES = 300;
const MAX_IGNORED_LINES = 200;

function field(root: ParentNode, name: string): HTMLElement | null {
  return root.querySelector<HTMLElement>(`[data-field="${name}"]`);
}

function el<K extends keyof HTMLElementTagNameMap>(doc: Document, tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const e = doc.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

const STATUS_LABEL: Record<FileStatus, string> = {
  queued: "planned", hashing: "reading", uploading: "uploading", downloading: "downloading", done: "done", adopted: "identical",
  "needs-review": "needs review", blocked: "blocked", skipped: "skipped", failed: "failed",
};

export function renderPanel(panel: HTMLElement, s: PanelState): void {
  const doc = panel.ownerDocument;
  const show = (name: string, visible: boolean) => { const e = field(panel, name); if (e) e.hidden = !visible; };

  const unsupported = panel.querySelector<HTMLElement>("#unsupported");
  if (unsupported) unsupported.hidden = s.supported;

  const badge = field(panel, "status");
  if (badge) {
    const d = deriveStatus({
      supported: s.supported, connected: s.connected, needsPermission: s.needsPermission, unreachable: s.unreachable, running: s.running,
      runMode: s.runMode, offline: s.offline, outcome: s.outcome?.status ?? null, cancelled: s.cancelled, pending: s.pending === true,
      openConflicts: s.review.conflicts.length, lastScanAt: s.lastScanAt,
    });
    badge.textContent = d.text;
    badge.className = "badge " + d.tone;
  }
  const changes = field(panel, "server-changes");
  if (changes) { changes.textContent = s.serverChange; changes.hidden = s.serverChange === ""; }

  const binding = field(panel, "binding");
  if (binding) { binding.textContent = s.binding; binding.className = s.bindingClass ?? ""; }

  show("connect-controls", s.supported && !s.connected);
  const reconnect = panel.querySelector<HTMLElement>('[data-action="reconnect"]');
  if (reconnect) reconnect.hidden = !s.needsReconnect;
  const connect = panel.querySelector<HTMLElement>('[data-action="connect"]');
  if (connect) connect.hidden = s.connected;
  // The connect controls are also the place to reconnect a known binding whose handle needs attention.
  show("connect-controls", s.supported && (!s.connected || s.needsReconnect));
  show("run-controls", s.supported && s.connected);

  for (const b of panel.querySelectorAll<HTMLButtonElement>('[data-action="scan"],[data-action="sync"],[data-action="disconnect"]')) b.disabled = s.running || s.needsReconnect;
  const syncBtn = panel.querySelector<HTMLElement>('[data-action="sync"]');
  if (syncBtn) syncBtn.textContent = s.pending ? "Resume sync" : "Sync now";
  const cancel = panel.querySelector<HTMLElement>('[data-action="cancel"]');
  if (cancel) cancel.hidden = !s.running;

  const phase = field(panel, "phase");
  if (phase) phase.textContent = s.phase;
  const progress = field(panel, "progress") as HTMLProgressElement | null;
  if (progress) {
    progress.hidden = !(s.running && s.progress !== null);
    if (s.progress !== null) progress.value = Math.round(s.progress * 100);
  }

  const list = field(panel, "files");
  const heading = field(panel, "files-heading");
  if (list) {
    list.replaceChildren();
    const lines = [...s.files.entries()].slice(-MAX_FILE_LINES);
    for (const [path, f] of lines) {
      const li = el(doc, "li", undefined, `status-${f.status}`);
      li.append(el(doc, "span", STATUS_LABEL[f.status], "st"), el(doc, "span", path), ...(f.detail ? [el(doc, "span", f.detail, "detail")] : []));
      list.append(li);
    }
    if (heading) heading.hidden = lines.length === 0;
    if (s.files.size > MAX_FILE_LINES) list.prepend(el(doc, "li", `... ${s.files.size - MAX_FILE_LINES} earlier items not shown`));
  }

  renderSummary(panel, s.outcome);
  const conflicts = field(panel, "conflicts");
  if (conflicts) renderConflicts(conflicts, s.review);
}

function renderSummary(panel: HTMLElement, o: RunOutcome | null): void {
  const doc = panel.ownerDocument;
  const summary = field(panel, "summary");
  const ignoredBox = field(panel, "ignored-box");
  const ignoredList = field(panel, "ignored");
  if (!summary) return;
  summary.replaceChildren();
  if (ignoredBox) ignoredBox.hidden = true;
  if (!o) return;

  const box = el(doc, "div", undefined, "summary");
  box.append(el(doc, "p", o.message, o.status === "failed" || o.status === "needs-review" || o.status === "paused" ? `status-${o.status}` : undefined));
  const dl = el(doc, "dl");
  const row = (k: string, v: string | number) => { dl.append(el(doc, "dt", k), el(doc, "dd", String(v))); };
  row("Files scanned", o.scannedFiles);
  row("Folders scanned", o.scannedDirectories);
  if (o.mode === "sync") { row("Uploaded", o.done.uploaded); row("Downloaded", o.done.downloaded); row("Folders created", o.done.directories); row("Already identical", o.done.adopted); if (o.done.deleted) row("Deleted", o.done.deleted); }
  else { for (const [k, v] of Object.entries(o.planned)) row(k.toLowerCase().replaceAll("_", " "), v); }
  row("Ignored paths", o.ignoredCount);
  row("Files hashed / reused", `${o.hashed} / ${o.reusedHashes}`);
  box.append(dl);

  const section = (title: string, items: string[], cls?: string) => {
    if (!items.length) return;
    box.append(el(doc, "h3", `${title} (${items.length})`));
    const ul = el(doc, "ul", undefined, cls);
    for (const t of items.slice(0, 100)) ul.append(el(doc, "li", t));
    if (items.length > 100) ul.append(el(doc, "li", `... and ${items.length - 100} more`));
    box.append(ul);
  };
  if (o.deletions) box.append(renderDeletionReview(doc, o.deletions));
  section("Needs review", o.review.filter((r) => !r.kind.startsWith("PROPOSE_")).map((r) => `${r.path} - ${r.detail ?? r.kind}`), "status-needs-review");
  section("Failed", o.failures.map((f) => `${f.path || "(root)"} - ${f.message}`), "status-failed");
  section("Unsupported names (not synced)", o.unsupported.map((u) => `${u.path} - ${u.reason.replaceAll("_", " ")}`));
  section("Larger than the file size limit (not synced)", o.oversized);
  section("Notes", o.notices);
  summary.append(box);

  if (ignoredBox && ignoredList && o.ignored.length) {
    ignoredList.replaceChildren();
    for (const i of o.ignored.slice(0, MAX_IGNORED_LINES)) {
      const why = i.builtin ? "Git metadata is never synced" : i.rule ? `matched "${i.rule}" in ${i.source}` : "ignored";
      ignoredList.append(el(doc, "li", `${i.path}${i.directory ? "/" : ""} - ${why}`));
    }
    if (o.ignoredCount > MAX_IGNORED_LINES) ignoredList.append(el(doc, "li", `... and ${o.ignoredCount - Math.min(o.ignored.length, MAX_IGNORED_LINES)} more`));
    ignoredBox.hidden = false;
  }
}

export function mountStatus(doc: Document, text = "Ready (nothing syncs until you press Sync now)"): void {
  const e = doc.getElementById("coordinator-status");
  if (e) e.textContent = text;
}
