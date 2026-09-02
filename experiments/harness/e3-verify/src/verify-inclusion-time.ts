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
  throttleCpus: number | string;
  throttleMemoryMb: number | string;
}

export async function runInclusionVerifyTime(opts: RunInclusionVerifyTimeOptions): Promise<void> {
  const fixtures: InclusionFixture[] = JSON.parse(readFileSync(opts.fixturesPath, 'utf8'));
  const hostCpuModel = readHostCpuModel();
  const rows: (string | number)[][] = [];

  for (const fixture of fixtures) {
    const start = process.hrtime.bigint();
    const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
    const end = process.hrtime.bigint();
    if (!ok) {
      throw new Error(
        'fixture failed to verify (epochSize=' + fixture.epochSize + ', leafIndex=' + fixture.leafIndex + ')'
      );
    }
    const elapsedMs = Number(end - start) / 1e6;
    rows.push([opts.seed, fixture.epochSize, fixture.leafIndex, elapsedMs, opts.throttleCpus, opts.throttleMemoryMb, hostCpuModel]);
  }

  writeCsv(
    opts.csvPath,
    ['seed', 'epoch_size', 'leaf_index', 'elapsed_ms', 'throttle_cpus', 'throttle_memory_mb', 'host_cpu_model'],
    rows
  );
}
