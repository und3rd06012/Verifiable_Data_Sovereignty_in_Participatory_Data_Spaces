import { readFileSync } from 'node:fs';
import { verifyChain, Epoch } from '@benefits/audit-verify';
import { writeCsv } from './csv.js';
import { readHostCpuModel } from './host-info.js';

interface ChainFixture {
  chainLength: number;
  epochs: Epoch[];
}

export interface RunChainVerifyTimeOptions {
  seed: number;
  fixturesPath: string;
  csvPath: string;
  throttleCpus: number | string;
  throttleMemoryMb: number | string;
}

export async function runChainVerifyTime(opts: RunChainVerifyTimeOptions): Promise<void> {
  const fixtures: ChainFixture[] = JSON.parse(readFileSync(opts.fixturesPath, 'utf8'));
  const hostCpuModel = readHostCpuModel();
  const rows: (string | number)[][] = [];

  for (const fixture of fixtures) {
    const start = process.hrtime.bigint();
    const ok = await verifyChain(fixture.epochs);
    const end = process.hrtime.bigint();
    if (!ok) {
      throw new Error('fixture chain of length ' + fixture.chainLength + ' failed to verify');
    }
    const elapsedMs = Number(end - start) / 1e6;
    rows.push([opts.seed, fixture.chainLength, elapsedMs, opts.throttleCpus, opts.throttleMemoryMb, hostCpuModel]);
  }

  writeCsv(
    opts.csvPath,
    ['seed', 'chain_length', 'elapsed_ms', 'throttle_cpus', 'throttle_memory_mb', 'host_cpu_model'],
    rows
  );
}
