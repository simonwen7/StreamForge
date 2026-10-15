import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatMeasurement } from "../lib/numbers";
import { localLabel, stepMs, truncateUtc, utcLabel, type Granularity } from "../lib/time";
import type { Bucket } from "../api/types";

type Metric = "temperature" | "humidity" | "battery";

const META: Record<Metric, { title: string; unit: string; color: string; field: keyof Bucket }> = {
  temperature: { title: "Temperature", unit: "°C", color: "#1d4ed8", field: "temperatureAvg" },
  humidity: { title: "Humidity", unit: "%", color: "#0f766e", field: "humidityAvg" },
  battery: { title: "Battery", unit: "%", color: "#b45309", field: "batteryAvg" },
};

type Point = {
  bucketStart: string;
  value: number | null;
  count: number | null;
};

export function chartPoints(buckets: Bucket[], from: string, to: string, granularity: Granularity, metric: Metric): Point[] {
  const field = META[metric].field;
  const known = new Map<number, Bucket>();
  for (const bucket of buckets) {
    known.set(Date.parse(bucket.bucketStart), bucket);
  }
  const points: Point[] = [];
  const step = stepMs(granularity);
  const end = Date.parse(to);
  for (let cursor = truncateUtc(from, granularity); cursor < end; cursor += step) {
    const bucket = known.get(cursor);
    const raw = bucket ? bucket[field] : null;
    points.push({
      bucketStart: new Date(cursor).toISOString(),
      value: typeof raw === "number" ? raw : null,
      count: bucket ? bucket.readingCount : null,
    });
  }
  return points;
}

export function TrendChart({
  metric,
  buckets,
  from,
  to,
  granularity,
  timeZone,
}: {
  metric: Metric;
  buckets: Bucket[];
  from: string;
  to: string;
  granularity: Granularity;
  timeZone: string;
}) {
  const meta = META[metric];
  const points = chartPoints(buckets, from, to, granularity, metric);
  const accepted = buckets.filter((bucket) => typeof bucket[meta.field] === "number");
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby={`${metric}-title`}>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h3 id={`${metric}-title`} className="text-sm font-semibold text-slate-900">
          {meta.title}
        </h3>
        <span className="text-xs font-medium text-slate-500">{meta.unit}</span>
      </div>
      <div className="h-52">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid stroke="#e2e8f0" vertical={false} />
            <XAxis
              dataKey="bucketStart"
              tickFormatter={(value: string) => localLabel(value, timeZone).split(",")[0] ?? value}
              minTickGap={28}
              tick={{ fill: "#475569", fontSize: 11 }}
              axisLine={{ stroke: "#cbd5e1" }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: "#475569", fontSize: 11 }}
              axisLine={false}
              tickLine={false}
              width={42}
              tickFormatter={(value: number) => formatMeasurement(value)}
            />
            <Tooltip
              content={({ active, payload }) => {
                if (!active || !payload?.length) {
                  return null;
                }
                const point = payload[0]?.payload as Point;
                return (
                  <div className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs shadow-sm">
                    <p className="font-medium text-slate-900">UTC {utcLabel(point.bucketStart)}</p>
                    <p className="text-slate-600">{localLabel(point.bucketStart, timeZone)}</p>
                    <p className="mt-1 tabular-nums text-slate-900">
                      {point.value == null ? "No accepted readings" : `${formatMeasurement(point.value)} ${meta.unit}`}
                    </p>
                  </div>
                );
              }}
            />
            <Line
              type="linear"
              dataKey="value"
              name={meta.title}
              stroke={meta.color}
              strokeWidth={2}
              dot={false}
              connectNulls={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <p className="mt-2 text-xs leading-5 text-slate-500">
        Buckets are UTC {granularity === "hour" ? "hours" : "days"}. Labels use {timeZone}. Gaps are hours or days with no accepted readings, not zero measurements.
      </p>
      <details className="mt-3">
        <summary className="cursor-pointer text-sm font-medium text-blue-800" aria-label={`Show ${meta.title} bucket values`}>
          Show bucket values
        </summary>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full text-left text-sm" aria-label={`${meta.title} values`}>
            <caption className="sr-only">
              Accepted {meta.title.toLowerCase()} averages. Empty UTC buckets are omitted.
            </caption>
            <thead className="text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="py-1 pr-3 font-medium">UTC bucket</th>
                <th className="py-1 pr-3 font-medium">Local</th>
                <th className="py-1 pr-3 font-medium">Average</th>
                <th className="py-1 font-medium">Count</th>
              </tr>
            </thead>
            <tbody>
              {accepted.length === 0 ? (
                <tr>
                  <td colSpan={4} className="py-2 text-slate-600">
                    No accepted readings in this window.
                  </td>
                </tr>
              ) : (
                accepted.map((bucket) => (
                  <tr key={bucket.bucketStart} className="border-t border-slate-100">
                    <td className="py-1.5 pr-3 tabular-nums">{utcLabel(bucket.bucketStart)}</td>
                    <td className="py-1.5 pr-3">{localLabel(bucket.bucketStart, timeZone)}</td>
                    <td className="py-1.5 pr-3 tabular-nums">
                      {formatMeasurement(bucket[meta.field] as number | null)} {meta.unit}
                    </td>
                    <td className="py-1.5 tabular-nums">{bucket.readingCount}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}
