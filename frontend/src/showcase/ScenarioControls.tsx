import { INJECTED_DELIVERY_CAP } from "./sim/clock";
import type { SimState } from "./sim/types";

export function ScenarioControls({
  state,
  running,
  onSend,
  onSendMany,
  onSteady,
  onPause,
  onResume,
  onStep,
  onReset,
  onBroker,
  onWorker,
  onReplay,
  onPoison,
  onLate,
}: {
  state: SimState;
  running: boolean;
  onSend: () => void;
  onSendMany: () => void;
  onSteady: () => void;
  onPause: () => void;
  onResume: () => void;
  onStep: () => void;
  onReset: () => void;
  onBroker: () => void;
  onWorker: () => void;
  onReplay: () => void;
  onPoison: () => void;
  onLate: () => void;
}) {
  const injected = state.deliveries.filter((item) => item.replay || item.poisoned).length;
  const injectionCapped = injected >= INJECTED_DELIVERY_CAP;
  return (
    <section className="sf-controls" aria-label="Simulation controls">
      <div className="sf-control-primary">
        <button type="button" className="sf-button" data-testid="send-one" onClick={onSend}>Send events</button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="send-ten" onClick={onSendMany}>Send 10</button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="steady" aria-pressed={state.steady} onClick={onSteady}>
          {state.steady ? "Stop stream" : "Steady stream"}
        </button>
      </div>
      <div className="sf-control-row">
        {running ? (
          <button type="button" className="sf-button sf-button-quiet" data-testid="pause" onClick={onPause}>Pause</button>
        ) : (
          <button type="button" className="sf-button sf-button-quiet" data-testid="resume" onClick={onResume}>Resume</button>
        )}
        <button type="button" className="sf-button sf-button-quiet" data-testid="step" onClick={onStep}>Step</button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="reset" onClick={onReset}>Reset</button>
      </div>
      <div className="sf-control-row" aria-label="Faults">
        <button type="button" className="sf-button sf-button-quiet" data-testid="broker" aria-pressed={!state.brokerUp} onClick={onBroker}>
          {state.brokerUp ? "Pause broker" : "Restore broker"}
        </button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="worker" aria-pressed={!state.workerUp} onClick={onWorker}>
          {state.workerUp ? "Stop worker" : "Start worker"}
        </button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="replay" onClick={onReplay} disabled={injectionCapped}>Replay delivery</button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="poison" onClick={onPoison} disabled={injectionCapped}>Poison delivery</button>
        <button type="button" className="sf-button sf-button-quiet" data-testid="late" onClick={onLate}>Send late reading</button>
      </div>
    </section>
  );
}
