// Informational change indicator. It learns that the server moved (server-sent events, with a slow polling fallback)
// and tells the page; it can never scan, transfer or resume. The only callback is `onChange`, which the coordinator
// uses to show a note. Starting work stays a click.
export interface StreamLike {
  close(): void;
  onRevision(cb: (data: string) => void): void;
  onError(cb: () => void): void;
}

export interface WatcherDeps {
  openStream(url: string): StreamLike;
  fetchRevision(): Promise<number>;
  setTimer(fn: () => void | Promise<void>, ms: number): unknown;
  clearTimer(handle: unknown): void;
}

export const POLL_MS = 60_000;

export class ChangeWatcher {
  private stream: StreamLike | null = null;
  private timer: unknown = null;
  private baseline: number;
  private stopped = false;
  /** "stream" while server-sent events work, "polling" after they failed. */
  mode: "stream" | "polling" | "stopped" = "stopped";

  constructor(private readonly url: string, baseline: number, private readonly deps: WatcherDeps, private readonly onChange: (revision: number) => void) {
    this.baseline = baseline;
  }

  start(): void {
    this.stopped = false;
    try {
      const s = this.deps.openStream(this.url);
      this.stream = s;
      this.mode = "stream";
      s.onRevision((data) => this.seen(Number(data)));
      s.onError(() => { s.close(); if (this.stream === s) this.stream = null; this.poll(); });
    } catch { this.poll(); }
  }

  /** The page is now at [revision] (after its own run): later notifications compare against it. */
  acknowledge(revision: number): void { if (revision > this.baseline) this.baseline = revision; }

  stop(): void {
    this.stopped = true;
    this.mode = "stopped";
    this.stream?.close();
    this.stream = null;
    if (this.timer !== null) this.deps.clearTimer(this.timer);
    this.timer = null;
  }

  private seen(revision: number): void {
    if (this.stopped || !Number.isFinite(revision)) return;
    if (revision > this.baseline) { this.baseline = revision; this.onChange(revision); }
  }

  private poll(): void {
    if (this.stopped) return;
    this.mode = "polling";
    this.timer = this.deps.setTimer(async () => {
      this.timer = null;
      try { this.seen(await this.deps.fetchRevision()); } catch { /* offline or signed out: try again later */ }
      this.poll();
    }, POLL_MS);
  }
}
