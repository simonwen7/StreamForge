import { formatUtc } from "./sim/clock";
import type { StageName } from "./sim/selectors";
import type { SimState } from "./sim/types";

export function EventInspector({ state, stage }: { state: SimState; stage: StageName | null }) {
  const reading = state.readings.find((item) => item.eventId === state.selectedEventId) ?? null;
  if (!reading) {
    return (
      <aside className="sf-panel sf-inspector" aria-label="Event inspector">
        <h2>Event inspector</h2>
        <p className="sf-empty">Select a reading after you send events. The full id, outbox status, deliveries, and timeline show up here.</p>
      </aside>
    );
  }
  const outbox = state.outbox.find((item) => item.eventId === reading.eventId);
  const deliveries = state.deliveries.filter((item) => item.eventId === reading.eventId);
  const receipt = state.receipts.find((item) => item.eventId === reading.eventId);
  const dead = state.deadLetters.filter((item) => item.eventId === reading.eventId);
  const timeline = state.timeline.filter((item) => item.eventId === reading.eventId);
  return (
    <aside className="sf-panel sf-inspector" aria-label="Event inspector">
      <h2>Event inspector</h2>
      <p className="sf-stage">{stage}</p>
      <dl>
        <div><dt>Event</dt><dd><code>{reading.eventId}</code></dd></div>
        <div><dt>Device</dt><dd>{reading.deviceId}</dd></div>
        <div><dt>occurredAt</dt><dd>{formatUtc(reading.occurredAtMs)}</dd></div>
        <div><dt>receivedAt</dt><dd>{formatUtc(reading.receivedAtMs)}</dd></div>
        <div><dt>Outbox</dt><dd>{outbox ? `${outbox.status}, ${outbox.attempts} attempts` : "none"}</dd></div>
        <div><dt>Deliveries</dt><dd>{deliveries.length}</dd></div>
        <div><dt>Receipt</dt><dd>{receipt ? "Recorded" : "Not yet"}</dd></div>
        <div><dt>Dead letter</dt><dd>{dead.length === 0 ? "None" : dead.map((item) => item.reason).join(" ")}</dd></div>
      </dl>
      <ol className="sf-timeline" aria-label="Transition timeline">
        {timeline.map((entry, index) => (
          <li key={`${entry.name}-${index}`}>
            <span>{entry.name}</span>
            <small>{entry.detail}</small>
          </li>
        ))}
      </ol>
    </aside>
  );
}
