/** Fixed UTC anchor so a replay does not depend on the wall clock and timestamps are not 1970. */
export const SIMULATION_EPOCH_UTC_MS = Date.UTC(2026, 5, 15, 12, 0, 0);

export const TICK_CAP_MS = 250;
export const STEP_MS = 1_000;
export const PUBLISH_DELAY_MS = 700;
export const RETRY_DELAY_MS = 500;
export const WORKER_DELAY_MS = 400;
export const STEADY_INTERVAL_MS = 1_000;
export const EVENT_CAP = 200;
/** Extra broker copies created by replay or poison, not by ordinary publication. */
export const INJECTED_DELIVERY_CAP = 64;
export const LATE_OFFSET_MS = 2 * 60 * 60 * 1000;

export function simulationUtcMs(epochUtcMs: number, elapsedMs: number): number {
  return epochUtcMs + elapsedMs;
}

export function formatUtc(ms: number): string {
  return new Date(ms).toISOString().replace(".000Z", "Z");
}
