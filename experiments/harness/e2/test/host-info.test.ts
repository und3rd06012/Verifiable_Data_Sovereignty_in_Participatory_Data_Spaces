import { describe, it, expect } from 'vitest';
import { readHostCpuModel } from '../src/host-info';

describe('readHostCpuModel', () => {
  it('returns a non-empty string', () => {
    const model = readHostCpuModel();
    expect(typeof model).toBe('string');
    expect(model.length).toBeGreaterThan(0);
  });
});
