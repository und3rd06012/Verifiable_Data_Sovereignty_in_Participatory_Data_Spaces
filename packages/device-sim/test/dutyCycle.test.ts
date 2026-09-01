import { describe, it, expect } from 'vitest';
import { DutyCycle } from '../src/dutyCycle';

describe('DutyCycle', () => {
  it('is always online when onlineProbability is 1', () => {
    const cycle = new DutyCycle(1, () => 0.999);
    cycle.tick();
    expect(cycle.isOnline()).toBe(true);
  });

  it('is always offline when onlineProbability is 0', () => {
    const cycle = new DutyCycle(0, () => 0.001);
    cycle.tick();
    expect(cycle.isOnline()).toBe(false);
  });

  it('is deterministic given an injected rng', () => {
    const values = [0.9, 0.1, 0.9];
    let i = 0;
    const rng = () => values[i++];
    const cycle = new DutyCycle(0.5, rng);
    cycle.tick();
    expect(cycle.isOnline()).toBe(false); // 0.9 >= 0.5 -> offline
    cycle.tick();
    expect(cycle.isOnline()).toBe(true); // 0.1 < 0.5 -> online
    cycle.tick();
    expect(cycle.isOnline()).toBe(false); // 0.9 >= 0.5 -> offline
  });

  it('rejects a probability outside [0, 1]', () => {
    expect(() => new DutyCycle(1.5)).toThrow();
    expect(() => new DutyCycle(-0.1)).toThrow();
  });
});
