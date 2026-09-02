import { describe, it, expect } from 'vitest';
import { runSweep } from '../src/sweep';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { mkdtempSync } from 'node:fs';

// A small, fast sweep against the real, dedicated (unthrottled) e1 instance from
// Task 4, run manually (`docker compose -f docker-compose.e1.yml up -d couchdb-e1`)
// before this test. This is not run in the default `npm test` loop, since it
// depends on that container; it is run explicitly as part of this task's
// verification and again in Task 11's reproducibility check.
describe.skipIf(!process.env.E1_LIVE)('runSweep', () => {
  it('produces one CSV per arm with the expected header, for a reduced sweep', async () => {
    const outDir = mkdtempSync(join(tmpdir(), 'e1-sweep-'));
    await runSweep({
      baseUrl: 'http://localhost:5985',
      adminUser: 'admin',
      adminPassword: 'benefits',
      seed: 1,
      batchSizes: [10, 50],
      fieldCounts: [1, 5],
      decoyCounts: [0, 10],
      trials: 1,
      outDir
    });
    for (const arm of ['baseline', 'structural', 'signature']) {
      const path = join(outDir, arm + '.csv');
      expect(existsSync(path)).toBe(true);
      const content = readFileSync(path, 'utf8');
      const lines = content.trim().split('\n');
      expect(lines[0]).toBe(
        'seed,trial,batch_size,field_count,grant_set_size,wall_time_ms,throttle_cpus,throttle_memory_mb,host_cpu_model'
      );
      // 2 batch sizes x 2 field counts x 2 decoy counts x 1 trial = 8 rows, plus header.
      expect(lines.length).toBe(9);

      // Regression check for the C2 rename: grant_set_size is now always exactly
      // fieldCount + decoyCount, deterministically, with no floor/ceiling behavior.
      // fieldCounts includes 5 and decoyCounts includes both 0 and 10, so both the
      // zero-decoy baseline (grant_set_size === 5) and the decoy-bearing cell
      // (grant_set_size === 15) are present for field_count=5; check both to
      // meaningfully exercise decoyCount's effect on the realized grant set size.
      const dataRows = lines.slice(1).map((line) => line.split(','));
      const fieldCountFiveNoDecoyRow = dataRows.find((row) => row[3] === '5' && row[4] === '5');
      expect(fieldCountFiveNoDecoyRow).toBeDefined();
      const fieldCountFiveTenDecoyRow = dataRows.find((row) => row[3] === '5' && row[4] === '15');
      expect(fieldCountFiveTenDecoyRow).toBeDefined();
    }
  }, 120000);
});
