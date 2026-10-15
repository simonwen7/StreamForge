import { useMemo, useState } from "react";
import { formatMeasurement } from "../lib/numbers";
import { useShowcase } from "./ShowcaseProvider";
import { projectBuckets, type Granularity } from "./sim/analytics";
import { formatUtc } from "./sim/clock";
import { counters } from "./sim/selectors";
import { ShowcaseCharts } from "./ShowcaseCharts";

export function AnalyticsPage() {
  const { state } = useShowcase();
  const [deviceId, setDeviceId] = useState(state.devices[0]?.id ?? "room-north");
  const [granularity, setGranularity] = useState<Granularity>("hour");
  const now = state.epochUtcMs + state.elapsedMs;
  const from = state.epochUtcMs - 3 * 60 * 60 * 1000;
  const buckets = useMemo(
    () => projectBuckets(state.readings, deviceId, from, now + 1, granularity),
    [state.readings, deviceId, from, now, granularity],
  );
  const rows = state.readings
    .filter((reading) => reading.deviceId === deviceId && reading.occurredAtMs >= from && reading.occurredAtMs < now + 1)
    .sort((left, right) => right.occurredAtMs - left.occurredAtMs || right.eventId.localeCompare(left.eventId));
  const latestOccurred = rows.reduce((max, reading) => Math.max(max, reading.occurredAtMs), Number.NEGATIVE_INFINITY);
  const latestReceived = rows.reduce((max, reading) => Math.max(max, reading.receivedAtMs), Number.NEGATIVE_INFINITY);
  return (
    <div className="sf-analytics">
      <header className="sf-hero">
        <p className="sf-kicker">Synthetic browser-local telemetry</p>
        <h1>Analytics from accepted readings</h1>
        <p>
          Charts use the same accepted store as the pipeline. Worker receipts do not add or remove a point.
          Buckets are UTC {granularity === "hour" ? "hours" : "days"} on a half-open window. Empty buckets are gaps, not zeros.
          A past window can still change when a late occurredAt arrives. These timestamps are the simulation clock, not a live server.
        </p>
      </header>
      <div className="sf-control-row">
        <label>
          Device
          <select value={deviceId} onChange={(event) => setDeviceId(event.target.value)}>
            {state.devices.map((device) => (
              <option key={device.id} value={device.id}>{device.label}</option>
            ))}
          </select>
        </label>
        <label>
          Bucket
          <select value={granularity} onChange={(event) => setGranularity(event.target.value as Granularity)}>
            <option value="hour">UTC hour</option>
            <option value="day">UTC day</option>
          </select>
        </label>
      </div>
      <section className="sf-metrics" aria-label="Accepted analytics summary">
        <p><span>Accepted readings</span><strong data-testid="analytics-count">{rows.length}</strong><small>Selected device and time range</small></p>
        <p><span>Pipeline accepted</span><strong>{counters(state).accepted}</strong><small>Every device in this browser run</small></p>
        <p><span>Latest occurredAt</span><strong>{Number.isFinite(latestOccurred) ? formatUtc(latestOccurred) : "—"}</strong></p>
        <p><span>Latest receivedAt</span><strong>{Number.isFinite(latestReceived) ? formatUtc(latestReceived) : "—"}</strong></p>
      </section>
      {buckets.length === 0 ? <p className="sf-empty">No accepted readings for this device yet. Send events on the pipeline, including a late reading if you want an earlier hour to change.</p> : null}
      <ShowcaseCharts buckets={buckets} />
      <section className="sf-panel">
        <h2>Accepted readings</h2>
        <table className="sf-table">
          <caption>Newest occurredAt first. Values are synthetic thousandths displayed to three decimal places at most.</caption>
          <thead>
            <tr>
              <th>Event</th>
              <th>occurredAt</th>
              <th>receivedAt</th>
              <th>Temperature °C</th>
              <th>Humidity %</th>
              <th>Battery %</th>
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, 20).map((reading) => (
              <tr key={reading.eventId} data-bucket={formatUtc(reading.occurredAtMs)}>
                <td><code>{reading.eventId}</code></td>
                <td>{formatUtc(reading.occurredAtMs)}</td>
                <td>{formatUtc(reading.receivedAtMs)}</td>
                <td>{formatMeasurement(reading.temperatureMilli / 1000)}</td>
                <td>{formatMeasurement(reading.humidityMilli / 1000)}</td>
                <td>{formatMeasurement(reading.batteryMilli / 1000)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length > 20 ? <p className="sf-empty">Showing 20 of {rows.length} accepted readings.</p> : null}
      </section>
    </div>
  );
}
