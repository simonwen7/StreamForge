import { MetricsStrip } from "./MetricsStrip";
import { PipelineView } from "./PipelineView";
import { ScenarioControls } from "./ScenarioControls";
import { EventInspector } from "./EventInspector";
import { useShowcase } from "./ShowcaseProvider";
import { counters, stageFor } from "./sim/selectors";

export function PipelinePage() {
  const { state, running, dispatch, pause, resume, step, reset } = useShowcase();
  const view = counters(state);
  const selected = state.selectedEventId;
  return (
    <div className="sf-pipeline">
      <div className="sf-hero">
        <p className="sf-kicker">Interactive architecture simulation</p>
        <h1>See a telemetry event survive ingestion, a broker outage, and a duplicate delivery.</h1>
        <p>
          StreamForge accepts a reading and an outbox row together, then publishes to Kafka and records one worker receipt.
          This page runs that behavior in your browser. It is not a remote Kafka cluster.
        </p>
      </div>
      <ScenarioControls
        state={state}
        running={running}
        onSend={() => dispatch({ type: "ACCEPT" })}
        onSendMany={() => dispatch({ type: "ACCEPT", count: 10 })}
        onSteady={() => dispatch({ type: "SET_STEADY", on: !state.steady })}
        onPause={pause}
        onResume={resume}
        onStep={step}
        onReset={reset}
        onBroker={() => dispatch({ type: "SET_BROKER", up: !state.brokerUp })}
        onWorker={() => dispatch({ type: "SET_WORKER", up: !state.workerUp })}
        onReplay={() => selected && dispatch({ type: "REPLAY", eventId: selected })}
        onPoison={() => dispatch({ type: "INJECT_POISON", eventId: selected ?? undefined })}
        onLate={() => dispatch({ type: "ACCEPT", late: true })}
      />
      {state.notice ? <p className="sf-notice" role="status">{state.notice}</p> : null}
      <MetricsStrip counters={view} />
      <div className="sf-stage-grid">
        <PipelineView state={state} stage={(eventId) => stageFor(state, eventId)} onSelect={(eventId) => dispatch({ type: "SELECT", eventId })} />
        <EventInspector state={state} stage={selected ? stageFor(state, selected) : null} />
      </div>
    </div>
  );
}
