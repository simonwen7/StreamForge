import { formatUtc } from "./sim/clock";
import type { StageName } from "./sim/selectors";
import type { SimState } from "./sim/types";

export function ageLabel(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(seconds / 60);
  if (minutes === 0) {
    return `${seconds}s`;
  }
  return `${minutes}m ${seconds % 60}s`;
}

export function PipelineView({ state, stage, onSelect }: { state: SimState; stage: (eventId: string) => StageName; onSelect: (eventId: string) => void }) {
  const recent = [...state.readings].reverse().slice(0, 6);
  const selected = recent.find((reading) => reading.eventId === state.selectedEventId) ?? recent[0];
  const current = selected ? stage(selected.eventId) : null;
  return (
    <section className="sf-panel" aria-labelledby="pipeline-title">
      <div className="sf-panel-head">
        <h2 id="pipeline-title">Event pipeline</h2>
        <p>Analytics reads the accepted row. It does not wait for the worker.</p>
      </div>
      <ol className="sf-rail">
        {nodes(state, current).map((node) => (
          <li key={node.id} data-node={node.id} data-hot={node.hot ? "true" : "false"}>
            <span className="sf-node-index">{node.index}</span>
            <span className="sf-node-label">{node.label}</span>
            <span className="sf-node-value">{node.value}</span>
          </li>
        ))}
      </ol>
      <p className="sf-sidepath">Accepted telemetry → analytics. This path leaves ingestion and does not pass through Kafka.</p>
      {state.deadLetters.length > 0 ? <p className="sf-fault">Dead letter: a mismatched broker record was recorded separately from the accepted reading.</p> : null}
      {!state.brokerUp ? <p className="sf-fault">Broker paused. Accepted events remain pending. Ingestion is still open.</p> : null}
      {!state.workerUp ? <p className="sf-fault">Worker stopped. The broker can still receive published records.</p> : null}
      <ul className="sf-tape" aria-label="Recent accepted events">
        {recent.length === 0 ? <li className="sf-empty">Send events to watch one reading move through the outbox.</li> : null}
        {recent.map((reading) => {
          const name = stage(reading.eventId);
          return (
            <li key={reading.eventId}>
              <button
                type="button"
                className={reading.eventId === state.selectedEventId ? "sf-event is-selected" : "sf-event"}
                data-stage={name}
                onClick={() => onSelect(reading.eventId)}
              >
                <span className="sf-event-id">{reading.eventId}</span>
                <span className="sf-event-device">{reading.deviceId}</span>
                <span className="sf-stage">{name}</span>
                <span className="sf-event-time">{formatUtc(reading.occurredAtMs)}</span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function nodes(state: SimState, current: StageName | null) {
  const pending = state.outbox.filter((item) => item.status === "pending").length;
  const published = state.deliveries.filter((item) => !item.consumed).length;
  return [
    { id: "device", index: "01", label: "Device", value: `${state.devices.length} synthetic`, hot: current === "Accepted" },
    { id: "ingestion", index: "02", label: "Ingestion", value: `${state.readings.length} accepted`, hot: current === "Accepted" },
    { id: "outbox", index: "03", label: "Outbox", value: `${pending} pending`, hot: current === "Pending" || current === "Retried" || current === "Publication failed" },
    { id: "kafka", index: "04", label: "Kafka", value: state.brokerUp ? `${published} waiting` : "Paused", hot: current === "Published" || current === "Awaiting processing" },
    { id: "worker", index: "05", label: "Worker", value: state.workerUp ? "Running" : "Stopped", hot: current === "Awaiting processing" || current === "Dead-lettered" },
    { id: "receipt", index: "06", label: "Receipt", value: `${state.receipts.length} recorded`, hot: current === "Processed" },
  ];
}
