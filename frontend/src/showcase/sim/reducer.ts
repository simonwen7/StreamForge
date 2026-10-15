import { EVENT_CAP, INJECTED_DELIVERY_CAP, LATE_OFFSET_MS, PUBLISH_DELAY_MS, RETRY_DELAY_MS, SIMULATION_EPOCH_UTC_MS, STEADY_INTERVAL_MS, TICK_CAP_MS, WORKER_DELAY_MS, simulationUtcMs } from "./clock";
import { canonicalOf, deliveryIdFor, eventIdFor } from "./identity";
import { nextInt } from "./random";
import type { Delivery, Device, MeasurementOverride, OutboxRecord, Reading, SimAction, SimState, TimelineEntry } from "./types";

export const DEFAULT_SEED = 0x5f17;

const DEVICES: Device[] = [
  { id: "room-north", label: "North room", temperatureBase: 21_500 },
  { id: "room-south", label: "South room", temperatureBase: 24_800 },
];

export function createInitialState(seed = DEFAULT_SEED, epochUtcMs = SIMULATION_EPOCH_UTC_MS): SimState {
  return {
    epochUtcMs,
    elapsedMs: 0,
    rng: seed >>> 0,
    sequence: 1,
    devices: DEVICES,
    readings: [],
    outbox: [],
    deliveries: [],
    receipts: [],
    deadLetters: [],
    timeline: [],
    brokerUp: true,
    workerUp: true,
    steady: false,
    nextSteadyElapsedMs: STEADY_INTERVAL_MS,
    selectedEventId: null,
    notice: null,
    duplicatesAbsorbed: 0,
  };
}

export function reduce(state: SimState, action: SimAction): SimState {
  switch (action.type) {
    case "RESET":
      return createInitialState(action.seed ?? DEFAULT_SEED, action.epochUtcMs ?? SIMULATION_EPOCH_UTC_MS);
    case "SELECT":
      return { ...state, selectedEventId: action.eventId };
    case "SET_BROKER":
      return {
        ...state,
        brokerUp: action.up,
        notice: action.up ? "Broker restored. Pending rows can publish." : "Broker paused. Accepted readings stay pending.",
      };
    case "SET_WORKER":
      return {
        ...state,
        workerUp: action.up,
        notice: action.up ? "Worker started." : "Worker stopped. Publication can continue.",
      };
    case "SET_STEADY":
      return {
        ...state,
        steady: action.on,
        nextSteadyElapsedMs: state.elapsedMs + STEADY_INTERVAL_MS,
        notice: action.on ? "Steady stream on. One reading per logical second." : "Steady stream off.",
      };
    case "TICK":
      return tick(state, action.deltaMs, action.fromFrame);
    case "ACCEPT":
      return acceptMany(state, action.count ?? 1, action);
    case "REPLAY":
      return replay(state, action.eventId);
    case "INJECT_POISON":
      return poison(state, action.eventId);
    case "FAIL_ENCODING":
      return failEncoding(state, action.eventId);
    default:
      return state;
  }
}

function tick(state: SimState, deltaMs: number, fromFrame: boolean): SimState {
  const delta = fromFrame ? Math.min(Math.max(0, deltaMs), TICK_CAP_MS) : Math.max(0, deltaMs);
  if (delta <= 0) {
    return state;
  }
  let next: SimState = { ...state, elapsedMs: state.elapsedMs + delta, notice: null };
  if (next.steady && next.elapsedMs >= next.nextSteadyElapsedMs && next.readings.length < EVENT_CAP) {
    next = acceptOne(next, { type: "ACCEPT" });
    next = { ...next, nextSteadyElapsedMs: next.nextSteadyElapsedMs + STEADY_INTERVAL_MS };
  }
  next = publishEligible(next);
  next = consumeEligible(next);
  return next;
}

function acceptMany(state: SimState, count: number, action: Extract<SimAction, { type: "ACCEPT" }>): SimState {
  let next: SimState = { ...state, notice: null };
  const total = Math.max(1, count);
  for (let index = 0; index < total; index += 1) {
    next = acceptOne(next, action);
    if (next.notice && next.readings.length >= EVENT_CAP) {
      break;
    }
  }
  return next;
}

function acceptOne(state: SimState, action: Extract<SimAction, { type: "ACCEPT" }>): SimState {
  const deviceId = action.deviceId ?? state.devices[(Math.max(state.sequence, 1) - 1) % state.devices.length]?.id ?? "room-north";
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) {
    return { ...state, notice: `Unknown device ${deviceId}. No reading was accepted.` };
  }
  if (!action.eventId && state.readings.length >= EVENT_CAP) {
    return {
      ...state,
      notice: `This simulation keeps ${EVENT_CAP} accepted readings. Reset starts a new run.`,
    };
  }
  const receivedAtMs = simulationUtcMs(state.epochUtcMs, state.elapsedMs);
  const occurredAtMs = action.late ? receivedAtMs - LATE_OFFSET_MS : receivedAtMs;
  const generated = action.measurements ? { ...action.measurements, rng: state.rng } : generate(state, device, state.readings.length);
  const eventId = action.eventId ?? eventIdFor(state.sequence);
  const canonical = canonicalOf({
    deviceId,
    occurredAtMs,
    receivedAtMs,
    temperatureMilli: generated.temperatureMilli,
    humidityMilli: generated.humidityMilli,
    batteryMilli: generated.batteryMilli,
  });
  const existing = state.readings.find((reading) => reading.eventId === eventId);
  if (existing) {
    if (existing.canonical === canonical) {
      return appendTimeline(state, {
        eventId,
        elapsedMs: state.elapsedMs,
        name: "Identical retry",
        detail: "The same event id and payload are already accepted. No second reading was stored.",
      });
    }
    return {
      ...state,
      notice: "Event ID was already accepted with different content.",
    };
  }
  const reading: Reading = {
    eventId,
    deviceId,
    occurredAtMs,
    receivedAtMs,
    canonical,
    temperatureMilli: generated.temperatureMilli,
    humidityMilli: generated.humidityMilli,
    batteryMilli: generated.batteryMilli,
  };
  const outbox: OutboxRecord = {
    eventId,
    status: "pending",
    attempts: 0,
    notBeforeElapsedMs: state.elapsedMs + PUBLISH_DELAY_MS,
    createdElapsedMs: state.elapsedMs,
    errorCode: null,
  };
  return appendTimeline(
    {
      ...state,
      rng: action.measurements ? state.rng : generated.rng,
      sequence: action.eventId ? state.sequence : state.sequence + 1,
      readings: [...state.readings, reading],
      outbox: [...state.outbox, outbox],
      selectedEventId: state.selectedEventId ?? eventId,
      notice: null,
    },
    {
      eventId,
      elapsedMs: state.elapsedMs,
      name: "Accepted",
      detail: action.late
        ? "Accepted now. occurredAt is earlier, so the UTC analytics bucket can change."
        : "The reading and its outbox row are accepted together. The worker has not run.",
    },
  );
}

function generate(state: SimState, device: Device, index: number): MeasurementOverride & { rng: number } {
  const wave = nextInt(state.rng, 800);
  const humidity = nextInt(wave.seed, 4_000);
  const temperatureMilli = clamp(device.temperatureBase + wave.value - 400, -40_000, 125_000);
  const humidityMilli = clamp(45_000 + humidity.value, 0, 100_000);
  const batteryMilli = clamp(92_000 - index * 350, 0, 100_000);
  return { temperatureMilli, humidityMilli, batteryMilli, rng: humidity.seed };
}

function publishEligible(state: SimState): SimState {
  let next = state;
  let published = 0;
  for (const record of state.outbox) {
    if (published >= 4) {
      break;
    }
    if (record.status !== "pending" || record.notBeforeElapsedMs > next.elapsedMs) {
      continue;
    }
    next = publishOne(next, record.eventId);
    published += 1;
  }
  return next;
}

function publishOne(state: SimState, eventId: string): SimState {
  const record = state.outbox.find((item) => item.eventId === eventId);
  const reading = state.readings.find((item) => item.eventId === eventId);
  if (!record || !reading || record.status !== "pending") {
    return state;
  }
  if (!state.brokerUp) {
    const outbox = state.outbox.map((item) =>
      item.eventId === eventId
        ? {
            ...item,
            attempts: item.attempts + 1,
            notBeforeElapsedMs: state.elapsedMs + RETRY_DELAY_MS,
            errorCode: "KAFKA_SEND",
          }
        : item,
    );
    return appendTimeline(
      { ...state, outbox },
      {
        eventId,
        elapsedMs: state.elapsedMs,
        name: "Retry scheduled",
        detail: "The broker did not accept the record. The outbox row stays pending.",
      },
    );
  }
  const delivery: Delivery = {
    deliveryId: deliveryIdFor(state.sequence),
    eventId,
    canonical: reading.canonical,
    poisoned: false,
    replay: false,
    consumed: false,
    availableElapsedMs: state.elapsedMs + WORKER_DELAY_MS,
  };
  const outbox = state.outbox.map((item) =>
    item.eventId === eventId ? { ...item, status: "published" as const, attempts: item.attempts + 1, errorCode: null } : item,
  );
  return appendTimeline(
    {
      ...state,
      sequence: state.sequence + 1,
      outbox,
      deliveries: [...state.deliveries, delivery],
    },
    {
      eventId,
      elapsedMs: state.elapsedMs,
      name: "Published",
      detail: "Kafka accepted one copy. A processing receipt is still separate.",
    },
  );
}

function consumeEligible(state: SimState): SimState {
  if (!state.workerUp) {
    return state;
  }
  let next = state;
  let consumed = 0;
  for (const delivery of state.deliveries) {
    if (consumed >= 4) {
      break;
    }
    if (delivery.consumed || delivery.availableElapsedMs > next.elapsedMs) {
      continue;
    }
    next = consumeOne(next, delivery.deliveryId);
    consumed += 1;
  }
  return next;
}

function consumeOne(state: SimState, deliveryId: string): SimState {
  const delivery = state.deliveries.find((item) => item.deliveryId === deliveryId);
  if (!delivery || delivery.consumed) {
    return state;
  }
  const reading = state.readings.find((item) => item.eventId === delivery.eventId);
  const mismatch = delivery.poisoned || !reading || reading.canonical !== delivery.canonical;
  if (mismatch) {
    const recorded: SimState = {
      ...state,
      deadLetters: [
        ...state.deadLetters,
        {
          deliveryId,
          eventId: delivery.eventId,
          reason: "Persisted event does not match the Kafka record.",
          recordedElapsedMs: state.elapsedMs,
          sourceAcknowledged: false,
        },
      ],
    };
    const acknowledged: SimState = {
      ...recorded,
      deadLetters: recorded.deadLetters.map((item) =>
        item.deliveryId === deliveryId ? { ...item, sourceAcknowledged: true } : item,
      ),
      deliveries: recorded.deliveries.map((item) => (item.deliveryId === deliveryId ? { ...item, consumed: true } : item)),
    };
    return appendTimeline(
      appendTimeline(acknowledged, {
        eventId: delivery.eventId,
        elapsedMs: state.elapsedMs,
        name: "Dead letter recorded",
        detail: "The payload does not match the accepted reading. It was not stored as another measurement.",
      }),
      {
        eventId: delivery.eventId,
        elapsedMs: state.elapsedMs,
        name: "Source acknowledged",
        detail: "The source delivery is acknowledged after the dead letter is recorded. A crash between those steps can copy the dead letter.",
      },
    );
  }
  const already = state.receipts.some((item) => item.eventId === delivery.eventId);
  const deliveries = state.deliveries.map((item) => (item.deliveryId === deliveryId ? { ...item, consumed: true } : item));
  if (already) {
    return appendTimeline(
      { ...state, deliveries, duplicatesAbsorbed: state.duplicatesAbsorbed + 1 },
      {
        eventId: delivery.eventId,
        elapsedMs: state.elapsedMs,
        name: "Duplicate absorbed",
        detail: "Another Kafka delivery arrived. The existing receipt blocks a second business effect.",
      },
    );
  }
  return appendTimeline(
    {
      ...state,
      deliveries,
      receipts: [...state.receipts, { eventId: delivery.eventId, processedElapsedMs: state.elapsedMs }],
    },
    {
      eventId: delivery.eventId,
      elapsedMs: state.elapsedMs,
      name: "Receipt recorded",
      detail: "One durable receipt was written. Analytics already counted this reading at acceptance.",
    },
  );
}

function replay(state: SimState, eventId: string): SimState {
  const reading = state.readings.find((item) => item.eventId === eventId);
  const published = state.outbox.find((item) => item.eventId === eventId && item.status === "published");
  if (!reading || !published) {
    return { ...state, notice: "Replay needs a reading that has already been published." };
  }
  if (injectedDeliveries(state) >= INJECTED_DELIVERY_CAP) {
    return { ...state, notice: injectionCapNotice() };
  }
  const delivery: Delivery = {
    deliveryId: deliveryIdFor(state.sequence),
    eventId,
    canonical: reading.canonical,
    poisoned: false,
    replay: true,
    consumed: false,
    availableElapsedMs: state.elapsedMs,
  };
  return appendTimeline(
    { ...state, sequence: state.sequence + 1, deliveries: [...state.deliveries, delivery], notice: null, selectedEventId: eventId },
    {
      eventId,
      elapsedMs: state.elapsedMs,
      name: "Delivery replayed",
      detail: "At-least-once delivery can present the same record again. This is not exactly-once Kafka.",
    },
  );
}

function poison(state: SimState, eventId?: string): SimState {
  const target = eventId ?? [...state.outbox].reverse().find((item) => item.status === "published")?.eventId;
  if (!target || !state.readings.some((item) => item.eventId === target)) {
    return { ...state, notice: "Poison injection needs a published reading." };
  }
  if (injectedDeliveries(state) >= INJECTED_DELIVERY_CAP) {
    return { ...state, notice: injectionCapNotice() };
  }
  const delivery: Delivery = {
    deliveryId: deliveryIdFor(state.sequence),
    eventId: target,
    canonical: "mismatch",
    poisoned: true,
    replay: false,
    consumed: false,
    availableElapsedMs: state.elapsedMs,
  };
  return appendTimeline(
    { ...state, sequence: state.sequence + 1, deliveries: [...state.deliveries, delivery], notice: null, selectedEventId: target },
    {
      eventId: target,
      elapsedMs: state.elapsedMs,
      name: "Poison delivery",
      detail: "A broker copy disagrees with the accepted reading. The outbox row is not marked failed.",
    },
  );
}

function failEncoding(state: SimState, eventId: string): SimState {
  const record = state.outbox.find((item) => item.eventId === eventId);
  if (!record || record.status !== "pending") {
    return { ...state, notice: "Encoding failure applies only to a pending outbox row." };
  }
  const outbox = state.outbox.map((item) =>
    item.eventId === eventId ? { ...item, status: "failed" as const, errorCode: "CONTRACT_ENCODING" } : item,
  );
  return appendTimeline(
    { ...state, outbox, notice: null },
    {
      eventId,
      elapsedMs: state.elapsedMs,
      name: "Publication failed",
      detail: "The record cannot be encoded. This is not a worker dead letter.",
    },
  );
}

function injectedDeliveries(state: SimState): number {
  return state.deliveries.filter((item) => item.replay || item.poisoned).length;
}

function injectionCapNotice(): string {
  return `This simulation keeps ${INJECTED_DELIVERY_CAP} replay and poison deliveries. Reset starts a new run.`;
}

function appendTimeline(state: SimState, entry: TimelineEntry): SimState {
  const timeline = [...state.timeline, entry].slice(-800);
  return { ...state, timeline };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
