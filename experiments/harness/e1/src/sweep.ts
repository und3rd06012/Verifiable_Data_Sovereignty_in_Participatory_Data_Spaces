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

// A single _bulk_docs call above this size was verified live to crash CouchDB
// itself under the low-end device profile's 1 GB memory limit, independent of
// which arm is being measured or how the target database was created, an
// isolated write of 5000 freshly-generated documents into an empty database
// OOM-killed the container outright, while 1000 completed comfortably (memory
// only). Separately, the signature arm's per-document Ed25519 verification
// inside validate_doc_update is CPU-bound enough under the 1-CPU throttle that
// a 1000-document chunk took long enough to trip CouchDB's own internal
// fabric_worker_timeout (~60s), returning a 500 even though the container
// itself never crashed. 250 was verified live to clear both limits with
// margin for every arm, not just the cheaper ones, so one threshold is used
// for all three arms rather than special-casing the signature arm's chunk
// size differently from baseline/structural's. A batch above this size is
// chunked into multiple sequential _bulk_docs calls rather than sent as one,
// which is also a more realistic model of how a resource-constrained edge
// device would actually be driven in practice: no reasonable client sends a
// single multi-megabyte request body to a memory- and CPU-limited target
// either.
const MAX_SINGLE_WRITE_SIZE = 250;

async function writeBatch(
  client: ReturnType<typeof createCouchDbClient>,
  dbName: string,
  seed: number,
  batchOffset: number,
  batchSize: number,
  fieldCount: number,
  decoyCount: number
): Promise<number> {
  let totalElapsedMs = 0;
  let written = 0;

  while (written < batchSize) {
    const chunkSize = Math.min(MAX_SINGLE_WRITE_SIZE, batchSize - written);
    const docs = [];
    for (let i = 0; i < chunkSize; i++) {
      docs.push(
        generateSyntheticRecord({ seed, index: batchOffset + written + i, fieldCount, decoyCount })
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

    totalElapsedMs += Number(end - start) / 1e6;
    written += chunkSize;
  }

  return totalElapsedMs;
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
      // Recreated fresh at the start of each batch-size tier, not on every
      // cell: recreating per cell (567 delete/create cycles for the full
      // sweep) was verified live to exhaust a 1 CPU/1 GB CouchDB container
      // via cumulative churn overhead well before any single large batch
      // did, the container's Erlang VM was OOM-killed (os_mon closed)
      // around the 260th cycle, at a batch size of only 100. Recreating per
      // tier still prevents batch size from being confounded with database
      // growth (each tier starts empty and only accumulates the up-to-27
      // cells within that same tier, never carrying size forward from a
      // smaller batch size into a larger one), at roughly 1/27th the
      // database churn of recreating per cell.
      await installArm(client, arm, dbName);
      for (const fieldCount of opts.fieldCounts) {
        for (const decoyCount of opts.decoyCounts) {
          for (let trial = 0; trial < opts.trials; trial++) {
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
