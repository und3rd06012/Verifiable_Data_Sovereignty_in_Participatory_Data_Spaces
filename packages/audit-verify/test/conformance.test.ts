import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { leafHash } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';

const VECTOR_DIR = join(import.meta.dirname, '../../../conformance/audit-vectors');

describe('audit hashing conformance vectors', () => {
  const files = readdirSync(VECTOR_DIR).filter((f) => f.endsWith('.json')).sort();
  expect(files.length).toBeGreaterThan(0);

  for (const file of files) {
    it(`${file} hashes to the vector's expected values`, async () => {
      const vector = JSON.parse(readFileSync(join(VECTOR_DIR, file), 'utf-8'));
      const entries: AuditEntry[] = vector.entries;
      const firstLeaf = await leafHash(entries[0]);
      expect(firstLeaf).toBe(vector.expected.firstLeafHash);
    });
  }
});
