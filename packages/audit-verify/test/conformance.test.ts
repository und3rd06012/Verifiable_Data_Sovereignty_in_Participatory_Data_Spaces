import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { leafHash, nodeHash } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';

const VECTOR_DIR = join(import.meta.dirname, '../../../conformance/audit-vectors');

// Mirrors MerkleTree.root's exact algorithm (connector/extensions/benefits-audit):
// pairwise nodeHash, an odd trailing node at any level is promoted unhashed rather
// than duplicated. Test-local only: packages/audit-verify itself never builds a
// tree, only verifies one (spec section 8), but this is what makes the vectors
// actually prove both languages agree on `root`, not just on `leafHash`.
async function root(leafHashes: string[]): Promise<string> {
  let level = leafHashes;
  while (level.length > 1) {
    const next: string[] = [];
    let i = 0;
    while (i + 1 < level.length) {
      next.push(await nodeHash(level[i], level[i + 1]));
      i += 2;
    }
    if (i < level.length) {
      next.push(level[i]);
    }
    level = next;
  }
  return level[0];
}

describe('audit hashing conformance vectors', () => {
  const files = readdirSync(VECTOR_DIR).filter((f) => f.endsWith('.json')).sort();
  expect(files.length).toBeGreaterThan(0);

  for (const file of files) {
    it(`${file} hashes to the vector's expected values`, async () => {
      const vector = JSON.parse(readFileSync(join(VECTOR_DIR, file), 'utf-8'));
      const entries: AuditEntry[] = vector.entries;
      const leafHashes = await Promise.all(entries.map((e) => leafHash(e)));
      expect(leafHashes[0]).toBe(vector.expected.firstLeafHash);
      expect(await root(leafHashes)).toBe(vector.expected.root);
    });
  }
});
