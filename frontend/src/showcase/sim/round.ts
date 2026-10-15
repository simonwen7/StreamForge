/**
 * Mean of integer thousandths, rounded half away from zero to the nearest thousandth.
 * This matches PostgreSQL `ROUND(numeric, 3)` for values that are already exact thousandths.
 * It does not claim to reproduce every PostgreSQL NUMERIC result outside that range.
 */
export function meanThousandthsHalfAway(values: readonly number[]): number | null {
  if (values.length === 0) {
    return null;
  }
  let sum = 0;
  for (const value of values) {
    sum += value;
  }
  const count = values.length;
  const sign = sum < 0 ? -1 : 1;
  const absolute = Math.abs(sum);
  const quotient = Math.floor(absolute / count);
  const remainder = absolute % count;
  const rounded = remainder * 2 >= count ? quotient + 1 : quotient;
  return sign * rounded;
}

export function thousandthsToNumber(value: number | null): number | null {
  if (value == null) {
    return null;
  }
  return value / 1000;
}
