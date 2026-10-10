export interface Spread {
  n: number;
  median: number;
  min: number;
  max: number;
  p25: number;
  p75: number;
}

const tenth = (value: number): number => Math.round(value * 10) / 10;

function at(sorted: number[], fraction: number): number {
  if (sorted.length === 0) return Number.NaN;
  const position = (sorted.length - 1) * fraction;
  const low = Math.floor(position);
  const high = Math.ceil(position);
  return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
}

export function spread(values: readonly number[]): Spread {
  const sorted = values.filter(value => Number.isFinite(value)).sort((a, b) => a - b);
  if (sorted.length === 0) return { n: 0, median: Number.NaN, min: Number.NaN, max: Number.NaN, p25: Number.NaN, p75: Number.NaN };
  return { n: sorted.length, median: tenth(at(sorted, 0.5)), min: tenth(sorted[0]), max: tenth(sorted[sorted.length - 1]), p25: tenth(at(sorted, 0.25)), p75: tenth(at(sorted, 0.75)) };
}

export function spreadText(value: Spread, unit = ''): string {
  if (value.n === 0) return 'n/a';
  const suffix = unit ? ` ${unit}` : '';
  return value.n === 1 ? `${value.median}${suffix}` : `${value.median}${suffix} [${value.min}–${value.max}]`;
}

export function change(before: Spread, after: Spread): number {
  if (!Number.isFinite(before.median) || !Number.isFinite(after.median) || before.median === 0) return Number.NaN;
  return Math.round(((after.median - before.median) / Math.abs(before.median)) * 1000) / 10;
}

export function separated(before: Spread, after: Spread, lowerIsBetter = true): boolean {
  if (before.n === 0 || after.n === 0) return false;
  return lowerIsBetter ? after.max < before.min : after.min > before.max;
}
