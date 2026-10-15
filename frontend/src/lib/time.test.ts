import { describe, expect, it } from "vitest";
import { boundsFromPreset, spanMillis, validateBounds } from "./time";

describe("rolling windows", () => {
  it("advances last 24 hours with the supplied clock", () => {
    const ten = boundsFromPreset("24h", new Date("2026-10-15T14:00:00Z"));
    const later = boundsFromPreset("24h", new Date("2026-10-15T14:05:00Z"));
    expect(ten).toEqual({ from: "2026-10-14T14:00:00Z", to: "2026-10-15T14:00:00Z" });
    expect(later).toEqual({ from: "2026-10-14T14:05:00Z", to: "2026-10-15T14:05:00Z" });
    expect(spanMillis(ten)).toBe(24 * 60 * 60 * 1000);
  });

  it("keeps a caller-supplied custom range unchanged", () => {
    const custom = { from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" };
    expect(validateBounds(custom, 7 * 24 * 60 * 60 * 1000)).toBeNull();
    expect(custom).toEqual({ from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" });
  });

  it("rejects an inverted or oversized range before a request", () => {
    expect(validateBounds({ from: "2026-02-01T00:00:00Z", to: "2026-01-01T00:00:00Z" }, 86_400_000)).toMatch(/before/);
    expect(validateBounds({ from: "2026-01-01T00:00:00Z", to: "2026-03-01T00:00:00Z" }, 7 * 86_400_000)).toMatch(/longer/);
  });
});
