import { describe, it, expect } from 'vitest';
import { mulberry32, randomBytes } from '../src/rng';

describe('mulberry32', () => {
  it('produces the same sequence for the same seed', () => {
    const a = mulberry32(42);
    const b = mulberry32(42);
    const seqA = [a(), a(), a()];
    const seqB = [b(), b(), b()];
    expect(seqA).toEqual(seqB);
  });

  it('produces a different sequence for a different seed', () => {
    const a = mulberry32(42);
    const b = mulberry32(43);
    expect(a()).not.toBe(b());
  });

  it('stays within [0, 1)', () => {
    const rand = mulberry32(7);
    for (let i = 0; i < 100; i++) {
      const value = rand();
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThan(1);
    }
  });
});

describe('randomBytes', () => {
  it('produces the requested length', () => {
    const rand = mulberry32(1);
    expect(randomBytes(rand, 32).length).toBe(32);
  });

  it('is deterministic for a fixed seed', () => {
    const bytesA = randomBytes(mulberry32(99), 16);
    const bytesB = randomBytes(mulberry32(99), 16);
    expect(Array.from(bytesA)).toEqual(Array.from(bytesB));
  });
});
