import { describe, expect, it } from "vitest";
import { projectBuckets, truncateUtcMs } from "./analytics";
import { INJECTED_DELIVERY_CAP, SIMULATION_EPOCH_UTC_MS } from "./clock";
import { createInitialState, reduce } from "./reducer";
import { meanThousandthsHalfAway } from "./round";
import { counters } from "./selectors";
import type { SimState } from "./types";

const sample = { temperatureMilli: 21_500, humidityMilli: 48_000, batteryMilli: 90_000 };

function advance(state: SimState, ms: number): SimState {
  let next = state;
  let left = ms;
  while (left > 0) {
    const step = Math.min(left, 1_000);
    next = reduce(next, { type: "TICK", deltaMs: step, fromFrame: false });
    left -= step;
  }
  return next;
}

describe("simulation reducer", () => {
  it("accepts one reading and an outbox row without a receipt", () => {
    const accepted = reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", measurements: sample });
    const view = counters(accepted);
    expect(view.accepted).toBe(1);
    expect(view.pending).toBe(1);
    expect(view.receipts).toBe(0);
    expect(accepted.readings[0]?.occurredAtMs).toBe(SIMULATION_EPOCH_UTC_MS);
  });

  it("accepts a batch", () => {
    const accepted = reduce(createInitialState(), { type: "ACCEPT", count: 10, deviceId: "room-north", measurements: sample });
    expect(counters(accepted).accepted).toBe(10);
    expect(new Set(accepted.readings.map((reading) => reading.eventId)).size).toBe(10);
  });

  it("ignores an identical retry and rejects a conflicting payload", () => {
    const once = reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", eventId: "same", measurements: sample });
    const retry = reduce(once, { type: "ACCEPT", deviceId: "room-north", eventId: "same", measurements: sample });
    const conflict = reduce(once, {
      type: "ACCEPT",
      deviceId: "room-north",
      eventId: "same",
      measurements: { ...sample, temperatureMilli: 22_000 },
    });
    expect(counters(retry).accepted).toBe(1);
    expect(retry.timeline.at(-1)?.name).toBe("Identical retry");
    expect(counters(conflict).accepted).toBe(1);
    expect(conflict.notice).toMatch(/different content/);
  });

  it("rejects an unknown device", () => {
    const next = reduce(createInitialState(), { type: "ACCEPT", deviceId: "missing", measurements: sample });
    expect(counters(next).accepted).toBe(0);
    expect(next.notice).toMatch(/Unknown device/);
  });

  it("publishes and records one receipt", () => {
    const done = advance(reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", measurements: sample }), 2_000);
    const view = counters(done);
    expect(view.pending).toBe(0);
    expect(view.receipts).toBe(1);
    expect(view.accepted).toBe(1);
  });

  it("keeps accepted rows pending while the broker is down, then drains them", () => {
    let state = reduce(createInitialState(), { type: "SET_BROKER", up: false });
    state = reduce(state, { type: "ACCEPT", count: 3, deviceId: "room-north", measurements: sample });
    state = advance(state, 3_000);
    expect(counters(state).accepted).toBe(3);
    expect(counters(state).pending).toBe(3);
    expect(counters(state).oldestPendingAgeMs).toBeGreaterThan(0);
    expect(state.outbox.every((row) => row.status === "pending")).toBe(true);
    expect(state.outbox.some((row) => row.attempts > 0)).toBe(true);
    state = reduce(state, { type: "SET_BROKER", up: true });
    state = advance(state, 2_000);
    expect(counters(state).pending).toBe(0);
    expect(counters(state).receipts).toBe(3);
  });

  it("pauses receipts while the worker is down and resumes them", () => {
    let state = reduce(createInitialState(), { type: "SET_WORKER", up: false });
    state = reduce(state, { type: "ACCEPT", deviceId: "room-north", measurements: sample });
    state = advance(state, 2_000);
    expect(counters(state).accepted).toBe(1);
    expect(counters(state).pending).toBe(0);
    expect(counters(state).awaiting).toBe(1);
    expect(counters(state).receipts).toBe(0);
    state = reduce(state, { type: "SET_WORKER", up: true });
    state = advance(state, 1_000);
    expect(counters(state).receipts).toBe(1);
    expect(counters(state).awaiting).toBe(0);
  });

  it("absorbs a duplicate delivery without a second reading", () => {
    let state = advance(reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", measurements: sample }), 1_200);
    const eventId = state.readings[0]?.eventId ?? "";
    state = reduce(state, { type: "REPLAY", eventId });
    state = advance(state, 1_000);
    expect(counters(state).accepted).toBe(1);
    expect(counters(state).receipts).toBe(1);
    expect(counters(state).duplicatesAbsorbed).toBe(1);
    expect(state.deliveries.filter((item) => item.eventId === eventId)).toHaveLength(2);
  });

  it("records a dead letter before acknowledging a poison delivery", () => {
    let state = advance(reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", measurements: sample }), 1_200);
    const before = counters(state).accepted;
    state = reduce(state, { type: "INJECT_POISON" });
    expect(state.deadLetters).toHaveLength(0);
    state = advance(state, 500);
    expect(counters(state).accepted).toBe(before);
    expect(state.deadLetters).toHaveLength(1);
    expect(state.deadLetters[0]?.sourceAcknowledged).toBe(true);
    const names = state.timeline.filter((entry) => entry.name === "Dead letter recorded" || entry.name === "Source acknowledged").map((entry) => entry.name);
    expect(names).toEqual(["Dead letter recorded", "Source acknowledged"]);
    expect(state.outbox[0]?.status).toBe("published");
  });

  it("marks encoding failure without a dead letter", () => {
    let state = reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", measurements: sample });
    const eventId = state.readings[0]?.eventId ?? "";
    state = reduce(state, { type: "FAIL_ENCODING", eventId });
    expect(state.outbox[0]?.status).toBe("failed");
    expect(state.outbox[0]?.errorCode).toBe("CONTRACT_ENCODING");
    expect(state.deadLetters).toHaveLength(0);
    state = advance(state, 2_000);
    expect(counters(state).receipts).toBe(0);
    expect(counters(state).accepted).toBe(1);
  });

  it("lets a late reading change an earlier UTC hour", () => {
    const state = reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", late: true, measurements: sample });
    const reading = state.readings[0];
    expect(reading).toBeTruthy();
    if (!reading) {
      return;
    }
    expect(reading.occurredAtMs).toBe(reading.receivedAtMs - 2 * 60 * 60 * 1000);
    const buckets = projectBuckets(state.readings, "room-north", reading.occurredAtMs, reading.occurredAtMs + 1, "hour");
    expect(buckets).toHaveLength(1);
    expect(buckets[0]?.bucketStartMs).toBe(truncateUtcMs(reading.occurredAtMs, "hour"));
    expect(buckets[0]?.bucketStartMs).not.toBe(truncateUtcMs(reading.receivedAtMs, "hour"));
  });

  it("replays the same seed and actions", () => {
    const run = () => {
      let state = createInitialState(99, SIMULATION_EPOCH_UTC_MS);
      state = reduce(state, { type: "ACCEPT", count: 4 });
      return advance(state, 2_000);
    };
    expect(run()).toEqual(run());
  });

  it("caps frame ticks, keeps time monotonic, and resets", () => {
    const jumped = reduce(createInitialState(), { type: "TICK", deltaMs: 10_000, fromFrame: true });
    expect(jumped.elapsedMs).toBe(250);
    const stepped = reduce(jumped, { type: "TICK", deltaMs: 1_000, fromFrame: false });
    expect(stepped.elapsedMs).toBe(1_250);
    const reset = reduce(stepped, { type: "RESET", seed: 3 });
    expect(reset.elapsedMs).toBe(0);
    expect(reset.readings).toHaveLength(0);
    expect(reset.rng).toBe(3);
  });

  it("stops at the accepted-event cap without dropping rows", () => {
    let state = createInitialState();
    state = reduce(state, { type: "ACCEPT", count: 200, deviceId: "room-north", measurements: sample });
    expect(counters(state).accepted).toBe(200);
    state = reduce(state, { type: "ACCEPT", deviceId: "room-north", measurements: sample });
    expect(counters(state).accepted).toBe(200);
    expect(state.notice).toMatch(/Reset/);
  });

  it("does not invent receipts while the worker has not run", () => {
    const state = reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-south", measurements: sample });
    expect(counters(state).receipts).toBe(0);
    expect(counters(state).accepted).toBe(1);
  });

  it("stops extra replay and poison deliveries without dropping an unprocessed copy", () => {
    let state = advance(reduce(createInitialState(), { type: "ACCEPT", deviceId: "room-north", measurements: sample }), 1_200);
    const eventId = state.readings[0]?.eventId ?? "";
    const published = state.deliveries.length;
    for (let index = 0; index < INJECTED_DELIVERY_CAP; index += 1) {
      state = reduce(state, index % 2 === 0 ? { type: "REPLAY", eventId } : { type: "INJECT_POISON" });
    }
    const capped = state.deliveries.length;
    expect(capped - published).toBe(INJECTED_DELIVERY_CAP);
    expect(state.deliveries.some((item) => !item.consumed)).toBe(true);
    const blockedReplay = reduce(state, { type: "REPLAY", eventId });
    const blockedPoison = reduce(state, { type: "INJECT_POISON" });
    expect(blockedReplay.deliveries).toHaveLength(capped);
    expect(blockedPoison.deliveries).toHaveLength(capped);
    expect(blockedReplay.notice).toMatch(/Reset starts a new run/);
    expect(counters(blockedReplay).accepted).toBe(1);
    expect(counters(blockedReplay).receipts).toBe(counters(state).receipts);
    const reset = reduce(blockedReplay, { type: "RESET" });
    expect(reset.deliveries).toHaveLength(0);
    expect(reset.notice).toBeNull();
  });
});

describe("analytics projection", () => {
  it("uses half-open UTC hour and day windows and omits empty buckets", () => {
    const hour = Date.UTC(2026, 5, 15, 12, 0, 0);
    const nextHour = hour + 60 * 60 * 1000;
    const day = Date.UTC(2026, 5, 15);
    const nextDay = Date.UTC(2026, 5, 16);
    expect(truncateUtcMs(hour + 30 * 60 * 1000, "hour")).toBe(hour);
    expect(truncateUtcMs(nextHour, "day")).toBe(day);
    expect(truncateUtcMs(nextDay, "day")).toBe(nextDay);
    let state = createInitialState();
    state = reduce(state, { type: "ACCEPT", deviceId: "room-north", measurements: sample });
    const inside = projectBuckets(state.readings, "room-north", hour, nextHour, "hour");
    const excluded = projectBuckets(state.readings, "room-north", nextHour, nextHour + 1, "hour");
    const otherDevice = projectBuckets(state.readings, "room-south", hour, nextHour, "hour");
    expect(inside).toHaveLength(1);
    expect(excluded).toHaveLength(0);
    expect(otherDevice).toHaveLength(0);
    expect(inside[0]?.temperatureAvg).toBe(21.5);
  });

  it("returns null averages for an empty set and rounds halves away from zero", () => {
    expect(meanThousandthsHalfAway([])).toBeNull();
    expect(meanThousandthsHalfAway([1, 2])).toBe(2);
    expect(meanThousandthsHalfAway([-1, -2])).toBe(-2);
    expect(meanThousandthsHalfAway([0])).toBe(0);
    expect(meanThousandthsHalfAway([21_705])).toBe(21_705);
  });
});
