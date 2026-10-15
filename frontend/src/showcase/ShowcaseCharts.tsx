import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatMeasurement } from "../lib/numbers";
import { formatUtc } from "./sim/clock";
import type { AnalyticsBucket } from "./sim/analytics";

const SERIES = [
  { key: "temperatureAvg" as const, title: "Temperature", unit: "°C", color: "#38bdf8" },
  { key: "humidityAvg" as const, title: "Humidity", unit: "%", color: "#34d399" },
  { key: "batteryAvg" as const, title: "Battery", unit: "%", color: "#fbbf24" },
];

export function ShowcaseCharts({ buckets }: { buckets: AnalyticsBucket[] }) {
  const data = buckets.map((bucket) => ({
    ...bucket,
    label: formatUtc(bucket.bucketStartMs),
  }));
  return (
    <div className="sf-charts">
      {SERIES.map((series) => (
        <section key={series.key} className="sf-panel" aria-labelledby={`${series.key}-title`}>
          <h2 id={`${series.key}-title`}>{series.title}</h2>
          <p className="sf-chart-unit">{series.unit}. Gaps mean that UTC bucket has no accepted readings.</p>
          <div className="sf-chart">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid stroke="rgb(148 163 184 / 0.18)" vertical={false} />
                <XAxis dataKey="label" tick={{ fill: "#9aa8c0", fontSize: 11 }} minTickGap={24} tickLine={false} axisLine={{ stroke: "rgb(148 163 184 / 0.3)" }} />
                <YAxis tick={{ fill: "#9aa8c0", fontSize: 11 }} width={42} axisLine={false} tickLine={false} tickFormatter={(value: number) => formatMeasurement(value)} />
                <Tooltip
                  content={({ active, payload }) => {
                    if (!active || !payload?.length) {
                      return null;
                    }
                    const point = payload[0]?.payload as AnalyticsBucket & { label: string };
                    const value = point[series.key];
                    return (
                      <div className="sf-tooltip">
                        <p>{point.label}</p>
                        <p>{value == null ? "No accepted readings" : `${formatMeasurement(value)} ${series.unit}`}</p>
                      </div>
                    );
                  }}
                />
                <Line type="linear" dataKey={series.key} stroke={series.color} strokeWidth={2} dot={false} connectNulls={false} isAnimationActive={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
          <table className="sf-table" aria-label={`${series.title} bucket values`}>
            <caption>{series.title} averages from accepted readings. Empty buckets are omitted.</caption>
            <thead>
              <tr><th>UTC bucket</th><th>Average</th><th>Count</th></tr>
            </thead>
            <tbody>
              {buckets.map((bucket) => (
                <tr key={bucket.bucketStartMs}>
                  <td>{formatUtc(bucket.bucketStartMs)}</td>
                  <td>{formatMeasurement(bucket[series.key])}</td>
                  <td>{bucket.readingCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  );
}
