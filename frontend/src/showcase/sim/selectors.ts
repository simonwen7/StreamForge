import { simulationUtcMs } from "./clock";
import type { SimState } from "./types";

export interface Counters {
  accepted: number;
  pending: number;
  oldestPendingAgeMs: number;
  awaiting: number;
  receipts: number;
  duplicatesAbsorbed: number;
  deadLetters: number;
}

export function counters(state: SimState): Counters {
  const pending = state.outbox.filter((item) => item.status === "pending" || item.status === "claimed");
  const oldest = pending.reduce<number | null>((min, item) => (min == null || item.createdElapsedMs < min ? item.createdElapsedMs : min), null);
  return {
    accepted: state.readings.length,
    pending: pending.length,
    oldestPendingAgeMs: oldest == null ? 0 : Math.max(0, state.elapsedMs - oldest),
    awaiting: state.deliveries.filter((item) => !item.consumed).length,
    receipts: state.receipts.length,
    duplicatesAbsorbed: state.duplicatesAbsorbed,
    deadLetters: state.deadLetters.length,
  };
}

export type StageName =
  | "Accepted"
  | "Pending"
  | "Retried"
  | "Published"
  | "Awaiting processing"
  | "Processed"
  | "Duplicate absorbed"
  | "Dead-lettered"
  | "Publication failed";

export function stageFor(state: SimState, eventId: string): StageName {
  const outbox = state.outbox.find((item) => item.eventId === eventId);
  const receipt = state.receipts.some((item) => item.eventId === eventId);
  const dead = state.deadLetters.some((item) => item.eventId === eventId);
  const waiting = state.deliveries.some((item) => item.eventId === eventId && !item.consumed && !item.poisoned);
  if (outbox?.status === "failed") {
    return "Publication failed";
  }
  if (dead && !receipt) {
    return "Dead-lettered";
  }
  if (state.duplicatesAbsorbed > 0 && state.timeline.some((item) => item.eventId === eventId && item.name === "Duplicate absorbed")) {
    return receipt ? "Processed" : "Duplicate absorbed";
  }
  if (receipt) {
    return "Processed";
  }
  if (waiting) {
    return "Awaiting processing";
  }
  if (outbox?.status === "published") {
    return "Published";
  }
  if (outbox && outbox.attempts > 0) {
    return "Retried";
  }
  if (outbox?.status === "pending") {
    return "Pending";
  }
  return "Accepted";
}

export function currentUtc(state: SimState): number {
  return simulationUtcMs(state.epochUtcMs, state.elapsedMs);
}
