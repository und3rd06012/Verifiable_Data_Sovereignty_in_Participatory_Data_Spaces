import { readFileSync } from 'node:fs';
import { verifyInclusion } from '@benefits/audit-verify';
import { writeCsv } from './csv.js';
import { readHostCpuModel } from './host-info.js';

interface InclusionFixture {
  epochSize: number;
  leafIndex: number;
  leaf: string;
  root: string;
  steps: { siblingHash: string; siblingIsRight: boolean }[];
}

export interface RunInclusionVerifyTimeOptions {
  seed: number;
  fixturesPath: string;
  csvPath: string;
  throttleCpus: string;
  throttleMemoryMb: string;
}

const REPETITIONS = 10;

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

export async function runInclusionVerifyTime(opts: RunInclusionVerifyTimeOptions): Promise<void> {
  const fixtures: InclusionFixture[] = JSON.parse(readFileSync(opts.fixturesPath, 'utf8'));
  const hostCpuModel = readHostCpuModel();
  const rows: (string | number)[][] = [];

  // Untimed warmup pass: lets the JS engine JIT-warm the verify path before
  // any measurement, so the first timed repetition isn't paying a one-time cost.
  for (const fixture of fixtures) {
    const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
    if (!ok) {
      throw new Error(
        'fixture failed to verify during warmup (epochSize=' + fixture.epochSize + ', leafIndex=' + fixture.leafIndex + ')'
      );
    }
  }

  for (const fixture of fixtures) {
    const elapsed: number[] = [];
    for (let rep = 0; rep < REPETITIONS; rep++) {
      const start = process.hrtime.bigint();
      const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
      const end = process.hrtime.bigint();
      if (!ok) {
        throw new Error(
          'fixture failed to verify (epochSize=' + fixture.epochSize + ', leafIndex=' + fixture.leafIndex + ')'
        );
      }
      elapsed.push(Number(end - start) / 1e6);
    }
    rows.push([opts.seed, fixture.epochSize, fixture.leafIndex, median(elapsed), opts.throttleCpus, opts.throttleMemoryMb, hostCpuModel]);
  }

  writeCsv(
    opts.csvPath,
    ['seed', 'epoch_size', 'leaf_index', 'elapsed_ms', 'throttle_cpus', 'throttle_memory_mb', 'host_cpu_model'],
    rows
  );
}
