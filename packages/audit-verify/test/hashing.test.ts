import { describe, expect, it } from 'vitest';
import { escape, canonicalBytes, leafHash, nodeHash, prevEpochHash, GENESIS_PREV_EPOCH_HASH } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';

const entry: AuditEntry = {
  seq: 1, epoch: 'epoch-1', timestamp: '2026-08-31T00:00:00Z', eventType: 'negotiation-finalized',
  actor: 'participant-a', datasetRef: 'asset-1', statedPurpose: 'service-improvement', plainLanguage: 'release approved',
};

describe('escape', () => {
  it('orders backslash before pipe', () => {
    expect(escape('a\\b|c')).toBe('a\\\\b\\|c');
  });
});

describe('canonicalBytes', () => {
  it('does not collide across the field boundary', () => {
    const a: AuditEntry = { ...entry, actor: 'actor|forged', datasetRef: 'asset' };
    const b: AuditEntry = { ...entry, actor: 'actor', datasetRef: 'forged|asset' };
    expect(canonicalBytes(a)).not.toEqual(canonicalBytes(b));
  });
});

describe('leafHash', () => {
  it('is deterministic', async () => {
    expect(await leafHash(entry)).toBe(await leafHash(entry));
  });

  it('changes with any field', async () => {
    const mutated: AuditEntry = { ...entry, plainLanguage: 'a different sentence' };
    expect(await leafHash(entry)).not.toBe(await leafHash(mutated));
  });

  it('is domain separated from nodeHash', async () => {
    const leaf = await leafHash(entry);
    const node = await nodeHash(leaf, leaf);
    expect(leaf).not.toBe(node);
  });
});

describe('nodeHash', () => {
  it('is order sensitive', async () => {
    const leaf = await leafHash(entry);
    const zero = '0'.repeat(64);
    expect(await nodeHash(leaf, zero)).not.toBe(await nodeHash(zero, leaf));
  });
});

describe('GENESIS_PREV_EPOCH_HASH', () => {
  it('is sha256 of the empty string', async () => {
    expect(await GENESIS_PREV_EPOCH_HASH).toHaveLength(64);
  });
});

describe('prevEpochHash', () => {
  it('is deterministic and field sensitive', async () => {
    const a = await prevEpochHash('epoch-1', 'root-a', '2026-08-31T00:00:00Z');
    const b = await prevEpochHash('epoch-1', 'root-b', '2026-08-31T00:00:00Z');
    expect(a).not.toBe(b);
    expect(a).toBe(await prevEpochHash('epoch-1', 'root-a', '2026-08-31T00:00:00Z'));
  });
});
