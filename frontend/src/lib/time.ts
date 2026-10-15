export type RelativePreset = "24h" | "7d" | "30d";
export type OverviewPreset = RelativePreset | "custom";
export type ReadingsPreset = "24h" | "7d" | "custom";
export type Granularity = "hour" | "day";

export type Bounds = {
  from: string;
  to: string;
};

const SPANS_MS: Record<RelativePreset, number> = {
  "24h": 24 * 60 * 60 * 1000,
  "7d": 7 * 24 * 60 * 60 * 1000,
  "30d": 30 * 24 * 60 * 60 * 1000,
};

export const HOUR_MAX_MS = 31 * 24 * 60 * 60 * 1000;
export const DAY_MAX_MS = 366 * 24 * 60 * 60 * 1000;
export const READINGS_MAX_MS = 7 * 24 * 60 * 60 * 1000;
export const REFRESH_MS = 15_000;

export function toUtcTimestamp(date: Date): string {
  return date.toISOString().replace(/\.000Z$/, "Z");
}

export function localInputToUtc(value: string): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return toUtcTimestamp(date);
}

export function boundsFromPreset(preset: RelativePreset, now: Date): Bounds {
  return {
    from: toUtcTimestamp(new Date(now.getTime() - SPANS_MS[preset])),
    to: toUtcTimestamp(now),
  };
}

export function spanMillis(bounds: Bounds): number {
  return Date.parse(bounds.to) - Date.parse(bounds.from);
}

export function validateBounds(bounds: Bounds, maxMs: number): string | null {
  const from = Date.parse(bounds.from);
  const to = Date.parse(bounds.to);
  if (!Number.isFinite(from) || !Number.isFinite(to)) {
    return "Enter a start and end time.";
  }
  if (from >= to) {
    return "The start must be before the end.";
  }
  if (to - from > maxMs) {
    return "That range is longer than this view allows.";
  }
  return null;
}

export function utcLabel(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return toUtcTimestamp(date);
}

export function localLabel(iso: string | null | undefined, timeZone: string): string {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
    timeZoneName: "short",
  }).format(date);
}

export function truncateUtc(iso: string, granularity: Granularity): number {
  const date = new Date(iso);
  if (granularity === "hour") {
    return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate(), date.getUTCHours());
  }
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

export function stepMs(granularity: Granularity): number {
  return granularity === "hour" ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000;
}
