import { ageLabel } from "./PipelineView";
import type { Counters } from "./sim/selectors";

const ITEMS: { key: keyof Counters; label: string; testId: string; format?: "age" }[] = [
  { key: "accepted", label: "Accepted readings", testId: "accepted-count" },
  { key: "pending", label: "Pending publication", testId: "pending-count" },
  { key: "oldestPendingAgeMs", label: "Oldest pending age", testId: "pending-age", format: "age" },
  { key: "awaiting", label: "Awaiting processing", testId: "awaiting-count" },
  { key: "receipts", label: "Processing receipts", testId: "receipt-count" },
  { key: "duplicatesAbsorbed", label: "Duplicates absorbed", testId: "duplicate-count" },
  { key: "deadLetters", label: "Dead letters", testId: "dlt-count" },
];

export function MetricsStrip({ counters }: { counters: Counters }) {
  return (
    <section className="sf-metrics" aria-label="Browser-local simulation counters">
      {ITEMS.map((item) => (
        <p key={item.key}>
          <span>{item.label}</span>
          <strong data-testid={item.testId}>{item.format === "age" ? ageLabel(counters[item.key]) : counters[item.key]}</strong>
        </p>
      ))}
    </section>
  );
}
