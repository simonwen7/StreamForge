import { createContext, useContext, useEffect, useMemo, useReducer, useRef, useState, type Dispatch, type ReactNode } from "react";
import { STEP_MS } from "./sim/clock";
import { createInitialState, reduce } from "./sim/reducer";
import type { SimAction, SimState } from "./sim/types";

interface ShowcaseControls {
  state: SimState;
  running: boolean;
  dispatch: Dispatch<SimAction>;
  pause: () => void;
  resume: () => void;
  step: () => void;
  reset: () => void;
}

const ShowcaseContext = createContext<ShowcaseControls | null>(null);

export function ShowcaseProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reduce, undefined, () => createInitialState());
  const [running, setRunning] = useState(true);
  const runningRef = useRef(running);
  runningRef.current = running;

  useEffect(() => {
    if (import.meta.env.MODE === "test") {
      return;
    }
    let frame = 0;
    let active = false;
    const stop = () => {
      active = false;
      cancelAnimationFrame(frame);
    };
    const start = () => {
      if (active || !runningRef.current || document.visibilityState === "hidden") {
        return;
      }
      active = true;
      let last = performance.now();
      const loop = (now: number) => {
        if (!runningRef.current || document.visibilityState === "hidden") {
          stop();
          return;
        }
        const delta = now - last;
        last = now;
        if (delta > 0) {
          dispatch({ type: "TICK", deltaMs: delta, fromFrame: true });
        }
        frame = requestAnimationFrame(loop);
      };
      frame = requestAnimationFrame(loop);
    };
    start();
    document.addEventListener("visibilitychange", start);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", start);
    };
  }, [running]);

  const api = useMemo<ShowcaseControls>(
    () => ({
      state,
      running,
      dispatch,
      pause: () => setRunning(false),
      resume: () => setRunning(true),
      step: () => dispatch({ type: "TICK", deltaMs: STEP_MS, fromFrame: false }),
      reset: () => dispatch({ type: "RESET" }),
    }),
    [state, running],
  );

  return <ShowcaseContext.Provider value={api}>{children}</ShowcaseContext.Provider>;
}

export function useShowcase(): ShowcaseControls {
  const value = useContext(ShowcaseContext);
  if (!value) {
    throw new Error("Showcase state is unavailable.");
  }
  return value;
}
