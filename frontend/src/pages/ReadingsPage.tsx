import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router";
import { useReadings } from "../api/queries";
import { ErrorPanel, LoadingBlock } from "../components/States";
import { useRefreshAnchor } from "../hooks/useRefreshAnchor";
import { formatMeasurement } from "../lib/numbers";
import {
  READINGS_MAX_MS,
  boundsFromPreset,
  localInputToUtc,
  localLabel,
  utcLabel,
  validateBounds,
  type Bounds,
  type ReadingsPreset,
} from "../lib/time";

export function ReadingsPage() {
  const { deviceId = "" } = useParams();
  const [preset, setPreset] = useState<ReadingsPreset>("24h");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [cursor, setCursor] = useState<string | null>(null);
  const [frozenBounds, setFrozenBounds] = useState<Bounds | null>(null);
  const { anchor, refreshNow } = useRefreshAnchor(preset);
  const queryClient = useQueryClient();
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const liveBounds = useMemo(() => {
    if (preset === "custom") {
      const from = localInputToUtc(customFrom);
      const to = localInputToUtc(customTo);
      return from && to ? { from, to } : null;
    }
    return boundsFromPreset(preset, anchor);
  }, [preset, anchor, customFrom, customTo]);
  // A cursor belongs to the window that issued it. Later pages keep that window
  // until refresh or a filter change, so the rolling clock does not reuse it.
  const bounds = cursor && frozenBounds ? frozenBounds : liveBounds;

  useEffect(() => {
    setCursor(null);
    setFrozenBounds(null);
  }, [deviceId, preset, customFrom, customTo]);

  const rangeError = bounds ? validateBounds(bounds, READINGS_MAX_MS) : preset === "custom" ? "Choose a custom start and end." : null;
  const readings = useReadings(deviceId, rangeError ? null : bounds, cursor);

  function resetPaging() {
    setCursor(null);
    setFrozenBounds(null);
  }

  function refresh() {
    resetPaging();
    if (preset === "custom") {
      void queryClient.invalidateQueries({ queryKey: ["readings", deviceId] });
      return;
    }
    refreshNow();
  }

  return (
    <div className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Readings</h1>
          <p className="mt-1 max-w-3xl text-sm leading-6 text-slate-600">
            Newest event time first. The first page follows the selected window. Later pages keep the window that issued their cursor until you refresh or change the range. There is no previous-page cursor.
          </p>
        </div>
        <button type="button" className="btn-secondary" onClick={refresh}>
          Refresh
        </button>
      </div>
      <div className="flex flex-wrap gap-2" role="group" aria-label="Readings range">
        {(["24h", "7d"] as const).map((id) => (
          <button key={id} type="button" className={preset === id ? "btn-primary" : "btn-secondary"} aria-pressed={preset === id} onClick={() => setPreset(id)}>
            {id === "24h" ? "Last 24 hours" : "Last 7 days"}
          </button>
        ))}
        <button type="button" className={preset === "custom" ? "btn-primary" : "btn-secondary"} aria-pressed={preset === "custom"} onClick={() => setPreset("custom")}>
          Custom range
        </button>
      </div>
      {preset === "custom" ? (
        <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-2">
          <label className="grid gap-1 text-sm font-medium" htmlFor="readings-from">
            Start
            <input id="readings-from" type="datetime-local" className="field" value={customFrom} onChange={(event) => setCustomFrom(event.target.value)} />
          </label>
          <label className="grid gap-1 text-sm font-medium" htmlFor="readings-to">
            End
            <input id="readings-to" type="datetime-local" className="field" value={customTo} onChange={(event) => setCustomTo(event.target.value)} />
          </label>
        </div>
      ) : null}
      {rangeError ? (
        <p role="alert" className="text-sm text-red-800">
          {rangeError}
        </p>
      ) : null}
      {readings.isPending ? <LoadingBlock label="Loading readings" /> : null}
      {readings.isError ? <ErrorPanel error={readings.error} /> : null}
      {readings.data ? (
        <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[44rem] text-left text-sm">
              <caption className="sr-only">Accepted readings, newest event time first</caption>
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-3 py-2 font-medium">Event time</th>
                  <th className="px-3 py-2 font-medium">Received</th>
                  <th className="px-3 py-2 font-medium">Temperature</th>
                  <th className="px-3 py-2 font-medium">Humidity</th>
                  <th className="px-3 py-2 font-medium">Battery</th>
                  <th className="px-3 py-2 font-medium">Event ID</th>
                </tr>
              </thead>
              <tbody>
                {readings.data.readings.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-3 py-8 text-slate-600">
                      No accepted readings in this window.
                    </td>
                  </tr>
                ) : (
                  readings.data.readings.map((reading) => (
                    <tr key={reading.eventId} className="border-t border-slate-100">
                      <td className="px-3 py-2">
                        <span className="block tabular-nums">{utcLabel(reading.occurredAt)}</span>
                        <span className="block text-xs text-slate-500">{localLabel(reading.occurredAt, timeZone)}</span>
                      </td>
                      <td className="px-3 py-2 tabular-nums">{utcLabel(reading.receivedAt)}</td>
                      <td className="px-3 py-2 tabular-nums">{formatMeasurement(reading.temperatureC)} °C</td>
                      <td className="px-3 py-2 tabular-nums">{formatMeasurement(reading.humidityPct)} %</td>
                      <td className="px-3 py-2 tabular-nums">{formatMeasurement(reading.batteryPct)} %</td>
                      <td className="px-3 py-2 font-mono text-xs">{reading.eventId}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 px-3 py-3">
            <button type="button" className="btn-secondary" disabled={cursor == null} onClick={resetPaging}>
              First page
            </button>
            {readings.data.nextCursor ? (
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  if (bounds) {
                    setFrozenBounds(bounds);
                  }
                  setCursor(readings.data.nextCursor);
                }}
              >
                Next page
              </button>
            ) : (
              <span className="text-sm text-slate-500">No further page</span>
            )}
          </div>
        </section>
      ) : null}
    </div>
  );
}
