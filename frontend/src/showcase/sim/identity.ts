import type { Reading } from "./types";

export function canonicalOf(reading: Pick<Reading, "deviceId" | "occurredAtMs" | "receivedAtMs" | "temperatureMilli" | "humidityMilli" | "batteryMilli">): string {
  return [
    reading.deviceId,
    reading.occurredAtMs,
    reading.receivedAtMs,
    reading.temperatureMilli,
    reading.humidityMilli,
    reading.batteryMilli,
  ].join("|");
}

export function eventIdFor(sequence: number): string {
  return `sf-evt-${sequence.toString(16).padStart(4, "0")}`;
}

export function deliveryIdFor(sequence: number): string {
  return `sf-del-${sequence.toString(16).padStart(4, "0")}`;
}
