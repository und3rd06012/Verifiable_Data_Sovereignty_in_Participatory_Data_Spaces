import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { verifyInclusion } from '@benefits/audit-verify';

interface InclusionFixture {
  epochSize: number;
  leafIndex: number;
  leaf: string;
  root: string;
  steps: { siblingHash: string; siblingIsRight: boolean }[];
}

describe('golden inclusion fixture (real Java-generated, multi-leaf)', () => {
  const fixtures: InclusionFixture[] = JSON.parse(
    readFileSync(join(__dirname, 'fixtures/golden-inclusion-fixture.json'), 'utf8')
  );

  it('contains at least one fixture with a non-empty proof (exercises real hashing, not the degenerate zero-step case)', () => {
    expect(fixtures.some((f) => f.steps.length > 0)).toBe(true);
  });

  fixtures.forEach((fixture) => {
    it('verifies leafIndex ' + fixture.leafIndex + ' (epochSize ' + fixture.epochSize + ', ' + fixture.steps.length + ' steps)', async () => {
      const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
      expect(ok).toBe(true);
    });
  });
});
