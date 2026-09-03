export function median(values: number[]): number {
  return percentile(values, 0.5);
}

export function iqr(values: number[]): number {
  return percentile(values, 0.75) - percentile(values, 0.25);
}

export function percentile(values: number[], p: number): number {
  if (values.length === 0) {
    throw new Error('cannot compute a percentile over zero values');
  }
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 1) {
    return sorted[0];
  }
  const rank = p * (sorted.length - 1);
  const lowerIndex = Math.floor(rank);
  const upperIndex = Math.ceil(rank);
  if (lowerIndex === upperIndex) {
    return sorted[lowerIndex];
  }
  const fraction = rank - lowerIndex;
  return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex]);
}
