import { describe, expect, it } from 'vitest';
import { verifyInclusion } from '../src/merkle.js';
import { leafHash } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';
import type { MerkleProof } from '../src/merkle.js';

const entry: AuditEntry = {
  seq: 0, epoch: 'epoch-1', timestamp: 't', eventType: 'type', actor: 'actor',
  datasetRef: 'asset', statedPurpose: 'purpose', plainLanguage: 'plain',
};

describe('verifyInclusion', () => {
  it('accepts a single-leaf tree where the leaf is the root and there are no steps', async () => {
    const leaf = await leafHash(entry);
    const proof: MerkleProof = { steps: [] };
    expect(await verifyInclusion(leaf, proof, leaf)).toBe(true);
  });

  it('rejects a proof against the wrong root', async () => {
    const leaf = await leafHash(entry);
    const proof: MerkleProof = { steps: [] };
    expect(await verifyInclusion(leaf, proof, '0'.repeat(64))).toBe(false);
  });

  it('rejects a tampered sibling hash', async () => {
    const leaf = await leafHash(entry);
    const other: AuditEntry = { ...entry, seq: 1 };
    const otherLeaf = await leafHash(other);
    const validProof: MerkleProof = { steps: [{ siblingHash: otherLeaf, siblingIsRight: true }] };
    const root = await import('../src/hashing.js').then((m) => m.nodeHash(leaf, otherLeaf));
    expect(await verifyInclusion(leaf, validProof, root)).toBe(true);

    const tamperedProof: MerkleProof = { steps: [{ siblingHash: 'f'.repeat(64), siblingIsRight: true }] };
    expect(await verifyInclusion(leaf, tamperedProof, root)).toBe(false);
  });
});
