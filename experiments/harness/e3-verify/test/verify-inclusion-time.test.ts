import { describe, it, expect } from 'vitest';
import { runInclusionVerifyTime } from '../src/verify-inclusion-time';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { verifyInclusion } from '@benefits/audit-verify';

describe('runInclusionVerifyTime', () => {
  it('writes one CSV row per fixture, and every fixture actually verifies', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'e3-verify-'));
    const fixturesPath = join(dir, 'inclusion-fixtures.json');
    const csvPath = join(dir, 'verify-inclusion-time.csv');

    const fixtures = [
      { epochSize: 5, leafIndex: 0, leaf: 'a'.repeat(64), root: 'a'.repeat(64), steps: [] },
      { epochSize: 5, leafIndex: 1, leaf: 'b'.repeat(64), root: 'b'.repeat(64), steps: [] }
    ];
    writeFileSync(fixturesPath, JSON.stringify(fixtures));

    for (const fixture of fixtures) {
      const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
      expect(ok).toBe(true);
    }

    await runInclusionVerifyTime({ seed: 1, fixturesPath, csvPath, throttleCpus: '', throttleMemoryMb: '' });

    const content = readFileSync(csvPath, 'utf8');
    const lines = content.trim().split('\n');
    expect(lines[0]).toBe('seed,epoch_size,leaf_index,elapsed_ms,throttle_cpus,throttle_memory_mb,host_cpu_model');
    expect(lines.length).toBe(3);
  });
});
