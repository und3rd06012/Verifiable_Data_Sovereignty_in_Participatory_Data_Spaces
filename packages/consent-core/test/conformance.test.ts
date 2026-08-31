import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { evaluateRecord } from '../src/evaluate';

const dir = join(__dirname, '../../../conformance/vectors');

describe('conformance vectors', () => {
  const files = readdirSync(dir).filter((f) => f.endsWith('.json')).sort();

  it('has vectors to run', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  files.forEach((file) => {
    const vector = JSON.parse(readFileSync(join(dir, file), 'utf8'));
    it(`${vector.id}: ${vector.description}`, () => {
      const decision = evaluateRecord(vector.record, vector.now);
      expect(decision).toEqual(vector.expected);
    });
  });
});
