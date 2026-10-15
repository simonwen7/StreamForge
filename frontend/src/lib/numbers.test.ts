import { describe, expect, it } from "vitest";
import { formatCount, formatMeasurement } from "./numbers";

describe("formatMeasurement", () => {
  it("formats scale-three values without trailing zeroes", () => {
    expect(formatMeasurement(21.705)).toBe("21.705");
    expect(formatMeasurement(JSON.parse("21.700") as number)).toBe("21.7");
    expect(formatMeasurement(21.7)).toBe("21.7");
    expect(formatMeasurement(0)).toBe("0");
    expect(formatMeasurement(0.001)).toBe("0.001");
    expect(formatMeasurement(-0.002)).toBe("-0.002");
    expect(formatMeasurement(-21.705)).toBe("-21.705");
  });

  it("does not turn null into zero or display non-finite numbers", () => {
    expect(formatMeasurement(null)).toBe("—");
    expect(formatMeasurement(undefined)).toBe("—");
    expect(formatMeasurement(Number.NaN)).toBe("—");
    expect(formatMeasurement(Number.POSITIVE_INFINITY)).toBe("—");
    expect(formatCount(null)).toBe("—");
    expect(formatCount(12)).toBe("12");
  });
});
