import { describe, it, expect } from 'vitest';
import { runChainVerifyTime } from '../src/verify-chain-time';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { GENESIS_PREV_EPOCH_HASH, prevEpochHash } from '@benefits/audit-verify';

describe('runChainVerifyTime', () => {
  it('writes one CSV row per chain, and every chain actually verifies', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'e3-chain-verify-'));
    const fixturesPath = join(dir, 'chain-fixtures.json');
    const csvPath = join(dir, 'verify-chain-time.csv');

    const genesis = await GENESIS_PREV_EPOCH_HASH;
    const epoch1 = { epochId: 'e1', merkleRoot: 'root1', prevEpochHash: genesis, sealedAt: '2026-08-30T00:00:00.000Z' };
    const nextHash = await prevEpochHash(epoch1.epochId, epoch1.merkleRoot, epoch1.sealedAt);
    const epoch2 = { epochId: 'e2', merkleRoot: 'root2', prevEpochHash: nextHash, sealedAt: '2026-08-30T00:00:01.000Z' };

    const fixtures = [
      { chainLength: 2, epochs: [epoch1, epoch2] },
      { chainLength: 1, epochs: [epoch1] }
    ];
    writeFileSync(fixturesPath, JSON.stringify(fixtures));

    await runChainVerifyTime({ seed: 1, fixturesPath, csvPath, throttleCpus: '', throttleMemoryMb: '' });

    const content = readFileSync(csvPath, 'utf8');
    const lines = content.trim().split('\n');
    expect(lines[0]).toBe('seed,chain_length,elapsed_ms,throttle_cpus,throttle_memory_mb,host_cpu_model');
    expect(lines.length).toBe(3);
  });
});
