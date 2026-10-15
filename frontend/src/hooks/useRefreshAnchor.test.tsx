import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useRefreshAnchor } from "./useRefreshAnchor";

describe("useRefreshAnchor", () => {
  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
  });

  it("advances a relative preset on the 15 second interval", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-15T14:00:00Z"));
    const { result } = renderHook(() => useRefreshAnchor("24h", 15_000));
    const started = result.current.anchor.getTime();
    act(() => {
      vi.advanceTimersByTime(15_000);
    });
    expect(result.current.anchor.getTime()).toBe(started + 15_000);
  });

  it("does not move the anchor while the document is hidden and resumes when visible", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-15T14:00:00Z"));
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    const { result } = renderHook(() => useRefreshAnchor("24h", 15_000));
    const started = result.current.anchor.getTime();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(result.current.anchor.getTime()).toBe(started);
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    act(() => {
      vi.setSystemTime(new Date("2026-10-15T14:01:00Z"));
      document.dispatchEvent(new Event("visibilitychange"));
    });
    expect(result.current.anchor.getTime()).toBe(new Date("2026-10-15T14:01:00Z").getTime());
  });

  it("does not schedule a moving anchor for a custom preset", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-15T14:00:00Z"));
    const { result } = renderHook(() => useRefreshAnchor("custom", 15_000));
    const started = result.current.anchor.getTime();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(result.current.anchor.getTime()).toBe(started);
  });
});
