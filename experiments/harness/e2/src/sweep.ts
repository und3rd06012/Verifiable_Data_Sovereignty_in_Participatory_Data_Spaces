import { runSweepPoint } from './sweep-point.js';
import { readHostCpuModel } from './host-info.js';
import { writeCsv } from './csv.js';
import { buildDesignDoc } from '@benefits/consent-couchdb';
import { join } from 'node:path';

export interface SweepOptions {
  dbName: string;
  seed: number;
  onlineProbabilities: number[];
  trials: number;
  outDir: string;
  throttleCpus?: number;
  throttleMemoryMb?: number;
}

export async function runSweep(opts: SweepOptions): Promise<void> {
  const authHeader = { authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') };
  const base = 'http://localhost:5984';
  await fetch(`${base}/${opts.dbName}`, { method: 'DELETE', headers: authHeader });
  await fetch(`${base}/${opts.dbName}`, { method: 'PUT', headers: authHeader });
  const dd = buildDesignDoc();
  await fetch(`${base}/${opts.dbName}/${dd._id}`, {
    method: 'PUT',
    headers: { ...authHeader, 'content-type': 'application/json' },
    body: JSON.stringify(dd)
  });

  const remoteUrl = `http://admin:benefits@localhost:5984/${opts.dbName}`;
  const hostCpuModel = readHostCpuModel();
  const throttleCpus = opts.throttleCpus ?? '';
  const throttleMemoryMb = opts.throttleMemoryMb ?? '';

  const rows: (string | number)[][] = [];
  for (let i = 0; i < opts.onlineProbabilities.length; i++) {
    const onlineProbability = opts.onlineProbabilities[i];
    const result = await runSweepPoint(opts.seed, onlineProbability, i, opts.trials, remoteUrl);
    rows.push([
      result.seed,
      result.onlineProbability,
      result.trials,
      result.propagationMedianTicks,
      result.propagationIqrTicks,
      result.propagationDataPoints,
      result.consistencyMedianTicks,
      result.consistencyIqrTicks,
      result.consistencyDataPoints,
      result.leakedRecordCount,
      result.unresolvedConflictCount,
      hostCpuModel,
      throttleCpus,
      throttleMemoryMb
    ]);
  }

  writeCsv(
    join(opts.outDir, 'partition.csv'),
    [
      'seed', 'online_probability', 'trials',
      'propagation_median_ticks', 'propagation_iqr_ticks', 'propagation_data_points',
      'consistency_median_ticks', 'consistency_iqr_ticks', 'consistency_data_points',
      'leaked_record_count', 'unresolved_conflict_count',
      'host_cpu_model', 'throttle_cpus', 'throttle_memory_mb'
    ],
    rows
  );
}
