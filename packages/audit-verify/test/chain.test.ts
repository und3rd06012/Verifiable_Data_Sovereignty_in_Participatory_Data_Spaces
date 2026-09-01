import { describe, expect, it } from 'vitest';
import { verifyChain } from '../src/chain.js';
import { GENESIS_PREV_EPOCH_HASH, prevEpochHash } from '../src/hashing.js';
import type { Epoch } from '../src/chain.js';

describe('verifyChain', () => {
  it('accepts an empty chain', async () => {
    expect(await verifyChain([])).toBe(true);
  });

  it('accepts a correctly linked two-epoch chain', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: await GENESIS_PREV_EPOCH_HASH, sealedAt: 't1' };
    const second: Epoch = {
      epochId: 'e2', merkleRoot: 'root-2',
      prevEpochHash: await prevEpochHash(first.epochId, first.merkleRoot, first.sealedAt), sealedAt: 't2',
    };
    expect(await verifyChain([first, second])).toBe(true);
  });

  it('rejects a chain whose first epoch does not point at the genesis hash', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: '0'.repeat(64), sealedAt: 't1' };
    expect(await verifyChain([first])).toBe(false);
  });

  it('rejects two reordered epochs', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: await GENESIS_PREV_EPOCH_HASH, sealedAt: 't1' };
    const second: Epoch = {
      epochId: 'e2', merkleRoot: 'root-2',
      prevEpochHash: await prevEpochHash(first.epochId, first.merkleRoot, first.sealedAt), sealedAt: 't2',
    };
    expect(await verifyChain([second, first])).toBe(false);
  });

  it('rejects a mutated root in an earlier epoch', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: await GENESIS_PREV_EPOCH_HASH, sealedAt: 't1' };
    const second: Epoch = {
      epochId: 'e2', merkleRoot: 'root-2',
      prevEpochHash: await prevEpochHash(first.epochId, first.merkleRoot, first.sealedAt), sealedAt: 't2',
    };
    const mutatedFirst: Epoch = { ...first, merkleRoot: 'tampered-root' };
    expect(await verifyChain([mutatedFirst, second])).toBe(false);
  });
});
