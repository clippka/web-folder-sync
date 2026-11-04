// Rendering for conflict review and deletion review. Every string that came from a file name, a device label or file
// content is written with textContent; nothing here is ever interpreted as HTML.
import { blobUrl, isDeletion, optionLabel, type Preview, type Resolution, type WireConflict } from "../reconcile/conflicts";
import type { DeletionReview } from "../reconcile/deletion";

export interface ReviewState {
  conflicts: WireConflict[];
  previews: Map<string, { local?: Preview | "loading"; server?: Preview | "loading" }>;
  note: string;
  busy: boolean;
  /** Whether the server lets decisions delete files (Step 8 gate). */
  deletionEnabled: boolean;
}

export function newReviewState(): ReviewState {
  return { conflicts: [], previews: new Map(), note: "", busy: false, deletionEnabled: false };
}

function el<K extends keyof HTMLElementTagNameMap>(doc: Document, tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const e = doc.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

export function formatBytes(n: number | null | undefined): string {
  if (n === null || n === undefined) return "-";
  if (n < 1024) return `${n} B`;
  const units = ["KiB", "MiB", "GiB"];
  let v = n / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]} (${n.toLocaleString("en-US")} B)`;
}

const fmtTime = (ms: number | null | undefined) => (ms ? new Date(ms).toLocaleString() : "-");

const KIND_TEXT: Record<WireConflict["kind"], string> = {
  CONTENT: "Edited in two places",
  DELETE_VS_EDIT: "Deleted in one place, edited in the other",
  STRUCTURAL: "A file and a folder share this name",
};

type Side = "local" | "server";

function sideBlock(doc: Document, c: WireConflict, side: Side, state: ReviewState): HTMLElement {
  const v = side === "local" ? c.local : c.server;
  const box = el(doc, "section", undefined, "variant");
  const title = side === "local"
    ? `In ${c.deviceLabel ? `"${c.deviceLabel}"` : "the folder that found this"}`
    : "On the server";
  box.append(el(doc, "h4", title));
  const dl = el(doc, "dl");
  const row = (k: string, val: string, full?: string) => { const dd = el(doc, "dd", val); if (full) dd.title = full; dl.append(el(doc, "dt", k), dd); };
  if (v.type === "ABSENT") {
    row("State", "Deleted (no file)");
  } else if (v.type === "DIRECTORY") {
    row("State", "Folder");
  } else {
    row("Size", formatBytes(v.size));
    row("SHA-256", (v.sha256 ?? "").slice(0, 16) + "…", v.sha256 ?? "");
  }
  row(side === "local" ? "Recorded" : "Last changed", side === "local" ? fmtTime(c.createdAt) : fmtTime(c.serverUpdatedAt));
  box.append(dl);

  if (v.type === "FILE" && v.blobId) {
    const wrap = el(doc, "div", undefined, "preview-box");
    const p = state.previews.get(c.id)?.[side];
    if (p === undefined) {
      const b = el(doc, "button", "Show text preview");
      b.type = "button"; b.dataset["action"] = "preview"; b.dataset["conflict"] = c.id; b.dataset["side"] = side;
      wrap.append(b);
    } else if (p === "loading") {
      wrap.append(el(doc, "p", "Loading preview…"));
    } else if (p.kind === "text" || p.kind === "empty") {
      const pre = el(doc, "pre", p.kind === "empty" ? "(empty file)" : p.text ?? "", "preview");
      wrap.append(pre);
      if (p.truncated) wrap.append(el(doc, "p", "Only the beginning is shown.", "note"));
    } else {
      wrap.append(el(doc, "p", p.kind === "binary" ? "Not shown: this is not plain text." : "Preview unavailable.", "note"));
    }
    // Attachment download: the server forces Content-Disposition: attachment and nosniff, so this never renders in the page.
    const a = el(doc, "a", "Download this version");
    a.href = blobUrl(c.spaceId, v.blobId);
    a.setAttribute("download", "");
    wrap.append(a);
    box.append(wrap);
  }
  return box;
}

export function renderConflicts(container: HTMLElement, state: ReviewState): void {
  const doc = container.ownerDocument;
  container.replaceChildren();
  if (!state.conflicts.length && !state.note) { container.hidden = true; return; }
  container.hidden = false;
  container.append(el(doc, "h2", `Conflicts (${state.conflicts.length})`));
  if (state.conflicts.length) {
    container.append(el(doc, "p", "Both versions are kept on the server until you decide. Other files keep syncing. Only the current version of a file is stored: whatever you do not keep is removed for good, with no history or trash.", "note"));
  }
  if (state.note) container.append(el(doc, "p", state.note, "conflict-note"));

  for (const c of state.conflicts) {
    const item = el(doc, "article", undefined, "conflict");
    item.dataset["conflict"] = c.id;
    item.append(el(doc, "h3", c.path), el(doc, "p", KIND_TEXT[c.kind], "kind"));
    const sides = el(doc, "div", undefined, "variants");
    sides.append(sideBlock(doc, c, "local", state), sideBlock(doc, c, "server", state));
    item.append(sides);

    const actions = el(doc, "div", undefined, "actions");
    const order: Resolution[] = c.kind === "DELETE_VS_EDIT"
      ? (c.local.type === "ABSENT" ? ["KEEP_SERVER", "KEEP_LOCAL"] : ["KEEP_LOCAL", "KEEP_SERVER"])
      : ["KEEP_LOCAL", "KEEP_SERVER", "KEEP_BOTH"];
    for (const r of order) {
      if (!c.options.includes(r)) continue;
      const { label, help } = optionLabel(c, r);
      const b = el(doc, "button", label);
      b.type = "button"; b.dataset["action"] = "resolve"; b.dataset["conflict"] = c.id; b.dataset["resolution"] = r;
      b.title = help;
      const deletes = isDeletion(c, r);
      if (deletes) b.className = "danger";
      b.disabled = state.busy || (deletes && !state.deletionEnabled);
      actions.append(b);
    }
    const skip = el(doc, "button", "Skip");
    skip.type = "button"; skip.dataset["action"] = "resolve"; skip.dataset["conflict"] = c.id; skip.dataset["resolution"] = "SKIP"; skip.disabled = state.busy;
    actions.append(skip);
    item.append(actions);
    if (order.some((r) => c.options.includes(r) && isDeletion(c, r)) && !state.deletionEnabled) {
      item.append(el(doc, "p", "Confirming a deletion is not available yet: this server keeps deletion switched off until it has passed its safety checks.", "note"));
    }
    container.append(item);
  }
}

export function renderDeletionReview(doc: Document, r: DeletionReview): HTMLElement {
  const box = el(doc, "section", undefined, r.emphasized ? "deletions emphasized" : "deletions");
  const total = r.fileCount + r.directoryCount;
  box.append(el(doc, "h3", `Deletions need your review (${r.fileCount} file${r.fileCount === 1 ? "" : "s"}${r.directoryCount ? `, ${r.directoryCount} folder${r.directoryCount === 1 ? "" : "s"}` : ""})`));
  if (r.emphasized) box.append(el(doc, "p", `Large deletion: ${r.reason}. Check this list carefully before confirming anything.`, "warning"));
  box.append(el(doc, "p", "Deleted files cannot be restored: FolderSync keeps no history, trash or per-file recovery. Nothing has been deleted.", "note"));
  const group = (title: string, items: DeletionReview["fromServer"]) => {
    if (!items.length) return;
    box.append(el(doc, "h4", `${title} (${items.length})`));
    const ul = el(doc, "ul", undefined, "files");
    for (const i of items.slice(0, 200)) ul.append(el(doc, "li", `${i.path}${i.directory ? "/" : ""}`));
    if (items.length > 200) ul.append(el(doc, "li", `… and ${items.length - 200} more`));
    box.append(ul);
  };
  group("Would be deleted from the server (and from other connected folders on their next sync)", r.fromServer);
  group("Would be deleted from this folder (they were deleted elsewhere)", r.fromThisFolder);
  if (r.executionEnabled) {
    box.append(el(doc, "p", `${total} item${total === 1 ? "" : "s"} wait for an explicit confirmation. Confirming runs a new sync that plans again first: only items that are still proposed and unchanged are deleted, files before their folders, and a folder that still holds anything else is kept.`, "note"));
    const b = el(doc, "button", `Delete these ${total} item${total === 1 ? "" : "s"} for good`, "danger");
    b.type = "button"; b.dataset["action"] = "confirm-deletions";
    box.append(b);
  } else {
    box.append(el(doc, "p", "Confirming deletions is not available: this server has deletion switched off. These files stay where they are.", "note"));
  }
  return box;
}
