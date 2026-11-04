// Ensures at most one coordinator instance exists per page, even if the script is evaluated twice.
const KEY = Symbol.for("foldersync.coordinator");

export interface CoordinatorSlot {
  readonly startedAt: number;
}

export function acquireCoordinatorSlot(target: Record<symbol, unknown> = globalThis as Record<symbol, unknown>): CoordinatorSlot | null {
  if (target[KEY]) return null;
  const slot: CoordinatorSlot = { startedAt: Date.now() };
  target[KEY] = slot;
  return slot;
}
