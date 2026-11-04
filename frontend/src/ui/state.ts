// Pure status derivation for the space panel. The spec lists the statuses a person can see; this is the one place that
// decides which applies, so it can be tested without a browser. "Up to date" always carries the time of the last
// completed scan and never claims continuous monitoring.
import type { RunStatus } from "../reconcile/run";

export type StatusLabel =
  | "Not connected" | "Permission required" | "Ready" | "Scanning" | "Needs review" | "Syncing"
  | "Up to date" | "Conflicts" | "Paused" | "Offline" | "Failed";

export type Tone = "ok" | "warn" | "bad" | "idle";

export interface StatusInput {
  supported: boolean;
  connected: boolean;
  /** Write permission is not granted for the connected folder. */
  needsPermission: boolean;
  /** The folder cannot be reached (moved, deleted, drive detached). */
  unreachable: boolean;
  running: boolean;
  runMode?: "scan" | "sync" | undefined;
  offline: boolean;
  outcome: RunStatus | null;
  /** The user cancelled the last run. */
  cancelled: boolean;
  /** A previous run left journals or staged uploads: the next Sync is a resume. */
  pending: boolean;
  openConflicts: number;
  lastScanAt: number | null;
}

export interface DerivedStatus { label: StatusLabel; tone: Tone; text: string }

export function deriveStatus(i: StatusInput, formatTime: (ms: number) => string = (ms) => new Date(ms).toLocaleString()): DerivedStatus {
  const mk = (label: StatusLabel, tone: Tone, extra = ""): DerivedStatus => ({ label, tone, text: extra ? `${label} ${extra}` : label });
  if (!i.supported) return mk("Not connected", "idle", "(this browser cannot sync local folders)");
  if (!i.connected) return mk("Not connected", "idle");
  if (i.needsPermission) return mk("Permission required", "warn");
  if (i.running) return i.runMode === "scan" ? mk("Scanning", "idle") : mk("Syncing", "idle");
  if (i.offline) return mk("Offline", "warn", "(nothing is lost; start again when the connection is back)");
  if (i.unreachable || i.outcome === "failed") return mk("Failed", "bad");
  if (i.cancelled || i.outcome === "cancelled" || i.outcome === "paused" || i.pending) {
    return mk("Paused", "warn", "(press Resume sync to continue)");
  }
  if (i.openConflicts > 0) return mk("Conflicts", "warn", `(${i.openConflicts} to review)`);
  if (i.outcome === "needs-review" || i.outcome === "partial") return mk("Needs review", "warn");
  if ((i.outcome === "up-to-date" || i.outcome === "completed") && i.lastScanAt) {
    return mk("Up to date", "ok", `(last scan ${formatTime(i.lastScanAt)})`);
  }
  if (i.lastScanAt && i.outcome === null) return mk("Ready", "idle", `(last scan ${formatTime(i.lastScanAt)})`);
  return mk("Ready", "idle");
}
