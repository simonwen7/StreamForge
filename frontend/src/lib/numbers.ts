/** Formats a measurement for display. Chart coordinates keep the original JavaScript number. */
export function formatMeasurement(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) {
    return "—";
  }
  const negative = value < 0;
  const scaled = Math.round(Math.abs(value) * 1000);
  const whole = Math.trunc(scaled / 1000);
  const fraction = scaled % 1000;
  const digits = fraction === 0 ? "" : `.${String(fraction).padStart(3, "0").replace(/0+$/, "")}`;
  return `${negative ? "-" : ""}${whole}${digits}`;
}

export function formatCount(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) {
    return "—";
  }
  return new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 }).format(value);
}
