import { useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useParams } from "react-router";
import { ApiError } from "../api/client";
import { useSeries, useSummary } from "../api/queries";
import { TrendChart } from "../components/TrendChart";
import { EmptyState, ErrorPanel, LoadingBlock } from "../components/States";
import { useRefreshAnchor } from "../hooks/useRefreshAnchor";
import { formatCount, formatMeasurement } from "../lib/numbers";
import {
  DAY_MAX_MS,
  HOUR_MAX_MS,
  boundsFromPreset,
  localInputToUtc,
  localLabel,
  spanMillis,
  utcLabel,
  validateBounds,
  type Granularity,
  type OverviewPreset,
  type RelativePreset,
} from "../lib/time";

const PRESETS: { id: RelativePreset; label: string }[] = [
  { id: "24h", label: "Last 24 hours" },
  { id: "7d", label: "Last 7 days" },
  { id: "30d", label: "Last 30 days" },
];

export function OverviewPage() {
  const { deviceId = "" } = useParams();
  const [preset, setPreset] = useState<OverviewPreset>("24h");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [granularity, setGranularity] = useState<Granularity>("hour");
  const { anchor, refreshNow } = useRefreshAnchor(preset);
  const queryClient = useQueryClient();
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const bounds = useMemo(() => {
    if (preset === "custom") {
      const from = localInputToUtc(customFrom);
      const to = localInputToUtc(customTo);
      return from && to ? { from, to } : null;
    }
    return boundsFromPreset(preset, anchor);
  }, [preset, anchor, customFrom, customTo]);

  const rangeError = bounds ? validateBounds(bounds, granularity === "hour" ? HOUR_MAX_MS : DAY_MAX_MS) : preset === "custom" ? "Choose a custom start and end." : null;
  const hourBlocked = bounds != null && spanMillis(bounds) > HOUR_MAX_MS;
  const activeGranularity: Granularity = hourBlocked ? "day" : granularity;
  const summary = useSummary(deviceId, rangeError ? null : bounds);
  const series = useSeries(deviceId, rangeError ? null : bounds, activeGranularity);

  function refresh() {
    if (preset === "custom") {
      void queryClient.invalidateQueries({ queryKey: ["summary", deviceId] });
      void queryClient.invalidateQueries({ queryKey: ["series", deviceId] });
      return;
    }
    refreshNow();
  }

  return (
    <div className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Overview</h1>
          <p className="mt-1 max-w-3xl text-sm leading-6 text-slate-600">
            Counts and charts are accepted telemetry. Pending publication is only the pending outbox. Awaiting processing is accepted events with no receipt. Those figures are not one pipeline funnel.
          </p>
        </div>
        <button type="button" className="btn-secondary" onClick={refresh}>
          Refresh
        </button>
      </div>

      <div className="flex flex-wrap gap-2" role="group" aria-label="Time range">
        {PRESETS.map((item) => (
          <button key={item.id} type="button" className={preset === item.id ? "btn-primary" : "btn-secondary"} aria-pressed={preset === item.id} onClick={() => setPreset(item.id)}>
            {item.label}
          </button>
        ))}
        <button type="button" className={preset === "custom" ? "btn-primary" : "btn-secondary"} aria-pressed={preset === "custom"} onClick={() => setPreset("custom")}>
          Custom range
        </button>
      </div>

      {preset === "custom" ? (
        <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-2">
          <label className="grid gap-1 text-sm font-medium" htmlFor="custom-from">
            Start
            <input id="custom-from" type="datetime-local" className="field" value={customFrom} onChange={(event) => setCustomFrom(event.target.value)} />
          </label>
          <label className="grid gap-1 text-sm font-medium" htmlFor="custom-to">
            End
            <input id="custom-to" type="datetime-local" className="field" value={customTo} onChange={(event) => setCustomTo(event.target.value)} />
          </label>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Granularity">
        <button type="button" className={activeGranularity === "hour" ? "btn-primary" : "btn-secondary"} aria-pressed={activeGranularity === "hour"} disabled={hourBlocked} onClick={() => setGranularity("hour")}>
          Hour
        </button>
        <button type="button" className={activeGranularity === "day" ? "btn-primary" : "btn-secondary"} aria-pressed={activeGranularity === "day"} onClick={() => setGranularity("day")}>
          Day
        </button>
        {hourBlocked ? <span className="text-sm text-slate-600">Hour view allows at most 31 days.</span> : null}
      </div>

      {bounds && !rangeError ? (
        <p className="text-xs text-slate-500">
          Window {utcLabel(bounds.from)} to {utcLabel(bounds.to)} UTC. The same bounds are used for the summary and the charts.
        </p>
      ) : null}
      {rangeError ? (
        <p role="alert" className="text-sm text-red-800">
          {rangeError}
        </p>
      ) : null}

      {summary.isPending || series.isPending ? <LoadingBlock label="Loading analytics" /> : null}
      {summary.isError ? <ErrorPanel error={summary.error} /> : null}
      {series.isError && !sameDeviceNotFound(summary.error, series.error) ? <ErrorPanel error={series.error} /> : null}

      {summary.data ? (
        <>
          {summary.data.windowOpen ? (
            <p className="rounded-lg bg-blue-50 px-3 py-2 text-sm text-blue-950">
              This window is still open. A later reading can change these results. A closed window can also change when a late event arrives.
            </p>
          ) : (
            <p className="rounded-lg bg-slate-100 px-3 py-2 text-sm text-slate-700">
              This window is closed on the clock. A late event whose event time falls inside it can still change the results.
            </p>
          )}
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Metric label="Accepted in this window" value={formatCount(summary.data.readingCount)} note="Accepted telemetry only" />
            <Metric
              label="Temperature"
              value={summary.data.readingCount === 0 ? "—" : `${formatMeasurement(summary.data.temperatureMin)} – ${formatMeasurement(summary.data.temperatureMax)} °C`}
              note={`Average ${formatMeasurement(summary.data.temperatureAvg)} °C`}
            />
            <Metric label="Humidity average" value={`${formatMeasurement(summary.data.humidityAvg)} %`} note="Accepted readings in this window" />
            <Metric label="Battery average" value={`${formatMeasurement(summary.data.batteryAvg)} %`} note="Accepted readings in this window" />
          </div>
          <div className="grid gap-3 lg:grid-cols-2">
            <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="text-sm font-semibold">Latest event in this window</h2>
              {summary.data.latest ? (
                <dl className="mt-2 grid gap-1 text-sm">
                  <div className="flex justify-between gap-3">
                    <dt className="text-slate-500">Event time</dt>
                    <dd className="tabular-nums">{utcLabel(summary.data.latest.occurredAt)}</dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-slate-500">Local</dt>
                    <dd>{localLabel(summary.data.latest.occurredAt, timeZone)}</dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-slate-500">Temperature</dt>
                    <dd className="tabular-nums">{formatMeasurement(summary.data.latest.temperatureC)} °C</dd>
                  </div>
                </dl>
              ) : (
                <p className="mt-2 text-sm text-slate-600">No accepted reading in this window.</p>
              )}
            </article>
            <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="text-sm font-semibold">Latest accepted at</h2>
              <p className="mt-1 text-xs text-slate-500">Ingestion time for the whole device. This is not a processing time.</p>
              <p className="mt-2 text-sm tabular-nums">{utcLabel(summary.data.history.latestReceivedAt)}</p>
              <p className="text-xs text-slate-500">{localLabel(summary.data.history.latestReceivedAt, timeZone)}</p>
            </article>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="text-sm font-semibold">Pending publication</h2>
              <p className="mt-2 text-2xl font-semibold tabular-nums">{formatCount(summary.data.publicationBacklog)}</p>
              <p className="mt-1 text-xs leading-5 text-slate-500">Entire device. Outbox rows still pending. Failed publication rows are not included.</p>
            </article>
            <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="text-sm font-semibold">Awaiting processing</h2>
              <p className="mt-2 text-2xl font-semibold tabular-nums">{formatCount(summary.data.processingBacklog)}</p>
              <p className="mt-1 text-xs leading-5 text-slate-500">Entire device. Accepted events that do not yet have a processing receipt.</p>
            </article>
          </div>
          {summary.data.readingCount === 0 ? (
            <EmptyState title="No accepted readings in this window">
              Run the simulator against this device, then refresh. Last 24 hours moves forward with the clock, so a new live run appears without selecting the device again.
            </EmptyState>
          ) : null}
        </>
      ) : null}

      {series.data && summary.data && summary.data.readingCount > 0 ? (
        <div className="grid gap-4">
          <TrendChart metric="temperature" buckets={series.data.buckets} from={series.data.from} to={series.data.to} granularity={series.data.granularity} timeZone={timeZone} />
          <TrendChart metric="humidity" buckets={series.data.buckets} from={series.data.from} to={series.data.to} granularity={series.data.granularity} timeZone={timeZone} />
          <TrendChart metric="battery" buckets={series.data.buckets} from={series.data.from} to={series.data.to} granularity={series.data.granularity} timeZone={timeZone} />
        </div>
      ) : null}
    </div>
  );
}

function sameDeviceNotFound(summaryError: unknown, seriesError: unknown): boolean {
  return summaryError instanceof ApiError && seriesError instanceof ApiError && summaryError.status === 404 && seriesError.status === 404;
}

function Metric({ label, value, note }: { label: string; value: string; note: string }) {
  const testId = label.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  return (
    <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</h2>
      <p className="mt-2 text-xl font-semibold tabular-nums text-slate-900" data-testid={testId}>
        {value}
      </p>
      <p className="mt-1 text-xs text-slate-500">{note}</p>
    </article>
  );
}
