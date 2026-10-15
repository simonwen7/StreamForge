/** Mulberry32. The seed is stored on the simulation so a replay repeats the same draws. */
export function nextUnit(seed: number): { value: number; seed: number } {
  let next = seed + 0x6d2b79f5;
  next = Math.imul(next ^ (next >>> 15), next | 1);
  next ^= next + Math.imul(next ^ (next >>> 7), next | 61);
  next = (next ^ (next >>> 14)) >>> 0;
  return { value: next / 4294967296, seed: next };
}

export function nextInt(seed: number, span: number): { value: number; seed: number } {
  const draw = nextUnit(seed);
  return { value: Math.floor(draw.value * span), seed: draw.seed };
}
