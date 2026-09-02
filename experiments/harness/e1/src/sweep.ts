import { createCouchDbClient } from './couchdb-client.js';
import { buildSignatureDesignDoc } from './build-signature-design-doc.js';
import { buildDesignDoc } from '@benefits/consent-couchdb';
import { generateSyntheticRecord } from './synthetic-records.js';
import { readHostCpuModel } from './host-info.js';
import { writeCsv } from './csv.js';
import { join } from 'node:path';

export interface SweepOptions {
  baseUrl: string;
  adminUser: string;
  adminPassword: string;
  seed: number;
  batchSizes: number[];
  fieldCounts: number[];
  decoyCounts: number[];
  trials: number;
  outDir: string;
  throttleCpus?: number;
  throttleMemoryMb?: number;
}

type Arm = 'baseline' | 'structural' | 'signature';
const ARMS: Arm[] = ['baseline', 'structural', 'signature'];

async function dbNameFor(arm: Arm, seed: number): Promise<string> {
  return 'e1-sweep-' + arm + '-' + seed;
}

async function installArm(
  client: ReturnType<typeof createCouchDbClient>,
  arm: Arm,
  dbName: string
): Promise<void> {
  await client.delete('/' + dbName);
  const created = await client.put('/' + dbName);
  if (created.status !== 201 && created.status !== 202) {
    throw new Error('failed to create database ' + dbName + ': ' + created.status);
  }
  if (arm === 'baseline') {
    return;
  }
  const dd = arm === 'structural' ? buildDesignDoc() : buildSignatureDesignDoc();
  const put = await client.put('/' + dbName + '/' + dd._id, dd);
  if (put.status !== 201 && put.status !== 202) {
    throw new Error('failed to install design doc for ' + arm + ': ' + put.status);
  }
}

async function writeBatch(
  client: ReturnType<typeof createCouchDbClient>,
  dbName: string,
  seed: number,
  batchOffset: number,
  batchSize: number,
  fieldCount: number,
  decoyCount: number
): Promise<number> {
  const docs = [];
  for (let i = 0; i < batchSize; i++) {
    docs.push(
      generateSyntheticRecord({ seed, index: batchOffset + i, fieldCount, decoyCount })
    );
  }
  const start = process.hrtime.bigint();
  const res = await client.post('/' + dbName + '/_bulk_docs', { docs });
  const end = process.hrtime.bigint();
  if (res.status !== 201) {
    throw new Error('bulk write failed with status ' + res.status);
  }
  // _bulk_docs returns 201 at the top level even when individual documents are
  // rejected by validate_doc_update; per-document failures only show up in the
  // response body's array as { id, error, reason }. Check for those explicitly
  // so a partial rejection fails the batch loudly rather than silently recording
  // wall_time_ms for a batch that didn't fully succeed.
  const results: Array<{ id?: string; error?: string; reason?: string }> = res.body;
  const rejected = results.filter((r) => r.error);
  if (rejected.length > 0) {
    throw new Error(
      'bulk write rejected ' + rejected.length + ' of ' + docs.length + ' documents: ' +
        rejected.map((r) => r.id + ':' + r.reason).join(', ')
    );
  }
  return Number(end - start) / 1e6;
}

export async function runSweep(opts: SweepOptions): Promise<void> {
  const client = createCouchDbClient(opts.baseUrl, opts.adminUser, opts.adminPassword);
  const hostCpuModel = readHostCpuModel();
  const throttleCpus = opts.throttleCpus ?? '';
  const throttleMemoryMb = opts.throttleMemoryMb ?? '';

  for (const arm of ARMS) {
    const dbName = await dbNameFor(arm, opts.seed);
    const rows: (string | number)[][] = [];
    let indexOffset = 0;

    for (const batchSize of opts.batchSizes) {
      for (const fieldCount of opts.fieldCounts) {
        for (const decoyCount of opts.decoyCounts) {
          for (let trial = 0; trial < opts.trials; trial++) {
            // Recreated fresh before every cell so database size never
            // accumulates across the sweep and confounds with batch size.
            await installArm(client, arm, dbName);
            const wallTimeMs = await writeBatch(
              client,
              dbName,
              opts.seed,
              indexOffset,
              batchSize,
              fieldCount,
              decoyCount
            );
            indexOffset += batchSize;
            const grantSetSize = fieldCount + decoyCount;
            rows.push([
              opts.seed,
              trial,
              batchSize,
              fieldCount,
              grantSetSize,
              wallTimeMs,
              throttleCpus,
              throttleMemoryMb,
              hostCpuModel
            ]);
          }
        }
      }
    }

    writeCsv(
      join(opts.outDir, arm + '.csv'),
      [
        'seed',
        'trial',
        'batch_size',
        'field_count',
        'grant_set_size',
        'wall_time_ms',
        'throttle_cpus',
        'throttle_memory_mb',
        'host_cpu_model'
      ],
      rows
    );

    await client.delete('/' + dbName);
  }
}
