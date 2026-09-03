import { describe, it, expect } from 'vitest';
import { writeCsv } from '../src/csv';
import { readFileSync, mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

describe('writeCsv', () => {
  it('writes a header row and quotes fields containing commas', () => {
    const dir = mkdtempSync(join(tmpdir(), 'e2-csv-'));
    const path = join(dir, 'out.csv');
    writeCsv(path, ['a', 'b'], [[1, 'x,y'], [2, 'z']]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('a,b\n1,"x,y"\n2,z\n');
  });
});
