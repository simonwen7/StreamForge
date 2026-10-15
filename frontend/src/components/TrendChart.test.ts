import { describe, expect, it } from "vitest";
import type { Bucket } from "../api/types";
import { chartPoints } from "./TrendChart";

const bucket: Bucket = {
  bucketStart: "2026-10-15T13:00:00Z",
  bucketEnd: "2026-10-15T14:00:00Z",
  readingCount: 1,
  temperatureMin: 21.7,
  temperatureMax: 21.705,
  temperatureAvg: 21.705,
  humidityAvg: null,
  batteryAvg: 0,
};

describe("chartPoints", () => {
  it("keeps missing UTC buckets as null instead of zero", () => {
    const points = chartPoints([bucket], "2026-10-15T12:00:00Z", "2026-10-15T15:00:00Z", "hour", "temperature");
    expect(points.map((point) => point.value)).toEqual([null, 21.705, null]);
    const humidity = chartPoints([bucket], "2026-10-15T13:00:00Z", "2026-10-15T14:00:00Z", "hour", "humidity");
    expect(humidity.map((point) => point.value)).toEqual([null]);
  });

  it("labels the accepted bucket in UTC and leaves local formatting to the viewer zone", () => {
    const points = chartPoints([bucket], "2026-10-15T13:00:00Z", "2026-10-15T14:00:00Z", "hour", "battery");
    expect(points[0]?.bucketStart).toBe("2026-10-15T13:00:00.000Z");
    expect(points[0]?.value).toBe(0);
  });
});
