export type ProblemDetail = {
  title?: string;
  detail?: string;
  status?: number;
  errors?: { field?: string; message?: string }[];
};

export type DeviceResponse = {
  deviceId: string;
  createdAt: string;
};

export type DevicePage = {
  devices: DeviceResponse[];
  limit: number;
  offset: number;
};

export type Reading = {
  eventId: string;
  occurredAt: string;
  receivedAt: string;
  temperatureC: number | null;
  humidityPct: number | null;
  batteryPct: number | null;
};

export type History = {
  earliestOccurredAt: string | null;
  latestOccurredAt: string | null;
  latestReceivedAt: string | null;
};

export type Summary = {
  deviceId: string;
  from: string;
  to: string;
  readingCount: number;
  temperatureMin: number | null;
  temperatureMax: number | null;
  temperatureAvg: number | null;
  humidityAvg: number | null;
  batteryAvg: number | null;
  latest: Reading | null;
  history: History;
  publicationBacklog: number;
  processingBacklog: number;
  windowOpen: boolean;
};

export type Bucket = {
  bucketStart: string;
  bucketEnd: string;
  readingCount: number;
  temperatureMin: number | null;
  temperatureMax: number | null;
  temperatureAvg: number | null;
  humidityAvg: number | null;
  batteryAvg: number | null;
};

export type Series = {
  deviceId: string;
  from: string;
  to: string;
  granularity: "hour" | "day";
  windowOpen: boolean;
  buckets: Bucket[];
};

export type ReadingPage = {
  deviceId: string;
  from: string;
  to: string;
  readings: Reading[];
  nextCursor: string | null;
};

export type Health = {
  status?: string;
};
