import { createCouchDbClient } from './couchdb-client';
import { buildSignatureDesignDoc } from './build-signature-design-doc';
import { buildDesignDoc } from '@benefits/consent-couchdb';
import { generateSyntheticRecord } from './synthetic-records';
import { readHostCpuModel } from './host-info';
import { writeCsv } from './csv';
import { join } from 'node:path';

export interface SweepOptions {
  baseUrl: string;
  adminUser: string;
  adminPassword: string;
  seed: number;
  batchSizes: number[];
  fieldCounts: number[];
  grantSetSizes: number[];
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
  grantSetSize: number
): Promise<number> {
  const docs = [];
  for (let i = 0; i < batchSize; i++) {
    docs.push(
      generateSyntheticRecord({ seed, index: batchOffset + i, fieldCount, grantSetSize })
    );
  }
  const start = process.hrtime.bigint();
  const res = await client.post('/' + dbName + '/_bulk_docs', { docs });
  const end = process.hrtime.bigint();
  if (res.status !== 201) {
    throw new Error('bulk write failed with status ' + res.status);
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
    await installArm(client, arm, dbName);

    const rows: (string | number)[][] = [];
    let indexOffset = 0;

    for (const batchSize of opts.batchSizes) {
      for (const fieldCount of opts.fieldCounts) {
        for (const grantSetSize of opts.grantSetSizes) {
          for (let trial = 0; trial < opts.trials; trial++) {
            const wallTimeMs = await writeBatch(
              client,
              dbName,
              opts.seed,
              indexOffset,
              batchSize,
              fieldCount,
              grantSetSize
            );
            indexOffset += batchSize;
            // generateSyntheticRecord (Task 2) guarantees every field has a matching
            // grant, and ConsentGrant covers exactly one field each, so a fully
            // consented record's realized grants.length is max(fieldCount,
            // grantSetSize), not the raw grantSetSize whenever fieldCount exceeds it
            // (see Task 2's own doc comment on this). Reporting the realized count
            // here, not the swept input, keeps this column an honest label of what
            // hasGrant()'s linear scan actually walked for this row.
            const realizedGrantSetSize = Math.max(fieldCount, grantSetSize);
            rows.push([
              opts.seed,
              trial,
              batchSize,
              fieldCount,
              realizedGrantSetSize,
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
