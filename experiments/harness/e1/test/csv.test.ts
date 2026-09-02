import { describe, it, expect, afterEach } from 'vitest';
import { writeCsv } from '../src/csv';
import { readFileSync, unlinkSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const path = join(tmpdir(), 'e1-csv-test.csv');

afterEach(() => {
  if (existsSync(path)) {
    unlinkSync(path);
  }
});

describe('writeCsv', () => {
  it('writes a header row and data rows separated by commas and newlines', () => {
    writeCsv(path, ['a', 'b'], [[1, 2], [3, 4]]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('a,b\n1,2\n3,4\n');
  });

  it('quotes a string field containing a comma or space', () => {
    writeCsv(path, ['name'], [['Intel(R) Core i7, 2.6GHz']]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('name\n"Intel(R) Core i7, 2.6GHz"\n');
  });

  it('does not quote a plain numeric or simple string field', () => {
    writeCsv(path, ['x'], [['baseline']]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('x\nbaseline\n');
  });
});
