import { describe, it, expect } from 'vitest';
import { median, iqr, percentile } from '../src/statistics';

describe('median', () => {
  it('returns the middle value for an odd count', () => {
    expect(median([1, 3, 2])).toBe(2);
  });

  it('interpolates for an even count', () => {
    expect(median([1, 2, 3, 4])).toBe(2.5);
  });
});

describe('iqr', () => {
  it('matches the known value for a uniform spread', () => {
    expect(iqr([1, 2, 3, 4, 5, 6, 7, 8])).toBeCloseTo(3.5, 9);
  });
});

describe('percentile', () => {
  it('returns the single value for a one-element list', () => {
    expect(percentile([5], 0.9)).toBe(5);
  });

  it('throws for an empty list', () => {
    expect(() => median([])).toThrow();
  });
});
