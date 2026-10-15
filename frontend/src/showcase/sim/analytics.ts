import { meanThousandthsHalfAway, thousandthsToNumber } from "./round";
import type { Reading } from "./types";

export type Granularity = "hour" | "day";

export interface AnalyticsBucket {
  bucketStartMs: number;
  temperatureAvg: number | null;
  humidityAvg: number | null;
  batteryAvg: number | null;
  readingCount: number;
}

export function truncateUtcMs(ms: number, granularity: Granularity): number {
  const date = new Date(ms);
  if (granularity === "hour") {
    return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate(), date.getUTCHours());
  }
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

export function projectBuckets(readings: readonly Reading[], deviceId: string, fromMs: number, toMs: number, granularity: Granularity): AnalyticsBucket[] {
  const groups = new Map<number, Reading[]>();
  for (const reading of readings) {
    if (reading.deviceId !== deviceId || reading.occurredAtMs < fromMs || reading.occurredAtMs >= toMs) {
      continue;
    }
    const start = truncateUtcMs(reading.occurredAtMs, granularity);
    const list = groups.get(start);
    if (list) {
      list.push(reading);
    } else {
      groups.set(start, [reading]);
    }
  }
  return [...groups.entries()]
    .sort((left, right) => left[0] - right[0])
    .map(([bucketStartMs, rows]) => ({
      bucketStartMs,
      readingCount: rows.length,
      temperatureAvg: thousandthsToNumber(meanThousandthsHalfAway(rows.map((row) => row.temperatureMilli))),
      humidityAvg: thousandthsToNumber(meanThousandthsHalfAway(rows.map((row) => row.humidityMilli))),
      batteryAvg: thousandthsToNumber(meanThousandthsHalfAway(rows.map((row) => row.batteryMilli))),
    }));
}

export function latestReading(readings: readonly Reading[], deviceId: string): Reading | null {
  let latest: Reading | null = null;
  for (const reading of readings) {
    if (reading.deviceId !== deviceId) {
      continue;
    }
    if (!latest || reading.receivedAtMs > latest.receivedAtMs) {
      latest = reading;
    }
  }
  return latest;
}
