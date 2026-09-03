import { describe, it, expect } from 'vitest';
import { runSweep } from '../src/sweep';
import { readFileSync, existsSync, mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

let couchDbReachable = false;
try {
  await fetch('http://localhost:5984');
  couchDbReachable = true;
} catch {
  couchDbReachable = false;
}

describe.skipIf(!couchDbReachable)('runSweep', () => {
  it('writes one header row plus one row per swept onlineProbability value', async () => {
    const outDir = mkdtempSync(join(tmpdir(), 'e2-sweep-'));
    await runSweep({
      dbName: 'e2-sweep-driver-test',
      seed: 1,
      onlineProbabilities: [0.5, 0.9],
      trials: 3,
      outDir
    });

    const path = join(outDir, 'partition.csv');
    expect(existsSync(path)).toBe(true);
    const lines = readFileSync(path, 'utf8').trim().split('\n');
    expect(lines).toHaveLength(3); // header + 2 sweep points
    expect(lines[0]).toBe(
      'seed,online_probability,trials,propagation_median_ticks,propagation_iqr_ticks,' +
      'propagation_data_points,consistency_median_ticks,consistency_iqr_ticks,' +
      'consistency_data_points,leaked_record_count,unresolved_conflict_count,' +
      'host_cpu_model,throttle_cpus,throttle_memory_mb'
    );
  }, 120000);
});
