export type OutboxStatus = "pending" | "claimed" | "published" | "failed";

export interface Device {
  id: string;
  label: string;
  temperatureBase: number;
}

export interface Reading {
  eventId: string;
  deviceId: string;
  occurredAtMs: number;
  receivedAtMs: number;
  temperatureMilli: number;
  humidityMilli: number;
  batteryMilli: number;
  canonical: string;
}

export interface OutboxRecord {
  eventId: string;
  status: OutboxStatus;
  attempts: number;
  notBeforeElapsedMs: number;
  createdElapsedMs: number;
  errorCode: string | null;
}

export interface Delivery {
  deliveryId: string;
  eventId: string;
  canonical: string;
  poisoned: boolean;
  replay: boolean;
  consumed: boolean;
  availableElapsedMs: number;
}

export interface Receipt {
  eventId: string;
  processedElapsedMs: number;
}

export interface DeadLetter {
  deliveryId: string;
  eventId: string;
  reason: string;
  recordedElapsedMs: number;
  sourceAcknowledged: boolean;
}

export interface TimelineEntry {
  eventId: string;
  elapsedMs: number;
  name: string;
  detail: string;
}

export interface SimState {
  epochUtcMs: number;
  elapsedMs: number;
  rng: number;
  sequence: number;
  devices: Device[];
  readings: Reading[];
  outbox: OutboxRecord[];
  deliveries: Delivery[];
  receipts: Receipt[];
  deadLetters: DeadLetter[];
  timeline: TimelineEntry[];
  brokerUp: boolean;
  workerUp: boolean;
  steady: boolean;
  nextSteadyElapsedMs: number;
  selectedEventId: string | null;
  notice: string | null;
  duplicatesAbsorbed: number;
}

export interface MeasurementOverride {
  temperatureMilli: number;
  humidityMilli: number;
  batteryMilli: number;
}

export type SimAction =
  | { type: "TICK"; deltaMs: number; fromFrame: boolean }
  | { type: "ACCEPT"; deviceId?: string; count?: number; late?: boolean; eventId?: string; measurements?: MeasurementOverride }
  | { type: "SET_BROKER"; up: boolean }
  | { type: "SET_WORKER"; up: boolean }
  | { type: "SET_STEADY"; on: boolean }
  | { type: "REPLAY"; eventId: string }
  | { type: "INJECT_POISON"; eventId?: string }
  | { type: "FAIL_ENCODING"; eventId: string }
  | { type: "SELECT"; eventId: string | null }
  | { type: "RESET"; seed?: number; epochUtcMs?: number };
