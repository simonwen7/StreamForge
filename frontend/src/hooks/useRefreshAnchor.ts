import { useEffect, useRef, useState } from "react";
import { REFRESH_MS } from "../lib/time";
import type { OverviewPreset, ReadingsPreset } from "../lib/time";

type Preset = OverviewPreset | ReadingsPreset;

/**
 * One logical clock for a refresh generation. Relative presets advance on the
 * interval and on resume. A custom preset keeps the anchor unused.
 */
export function useRefreshAnchor(preset: Preset, intervalMs = REFRESH_MS) {
  const [anchor, setAnchor] = useState(() => new Date());
  const seenPreset = useRef(preset);

  useEffect(() => {
    if (preset === "custom") {
      seenPreset.current = preset;
      return;
    }
    if (seenPreset.current !== preset) {
      setAnchor(new Date());
    }
    seenPreset.current = preset;

    let timer = 0;
    const arm = () => {
      window.clearInterval(timer);
      if (document.visibilityState !== "visible") {
        return;
      }
      timer = window.setInterval(() => setAnchor(new Date()), intervalMs);
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        setAnchor(new Date());
      }
      arm();
    };
    arm();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [preset, intervalMs]);

  return {
    anchor,
    refreshNow: () => setAnchor(new Date()),
  };
}
