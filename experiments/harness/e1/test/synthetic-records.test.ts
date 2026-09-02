import { describe, it, expect } from 'vitest';
import { generateSyntheticRecord } from '../src/synthetic-records';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';
import { base64Decode, utf8Encode } from '../src/portable-codec';
import { signedPayload } from '../src/canonicalize';

ed.hashes.sha512 = sha512;

describe('generateSyntheticRecord', () => {
  it('produces the requested field count and grant-set size', () => {
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 5, grantSetSize: 10 });
    expect(Object.keys(record.fields).length).toBe(5);
    expect((record.consent as any).grants.length).toBe(10);
  });

  it('is fully consented: every field has a matching, currently-active grant', () => {
    const record = generateSyntheticRecord({ seed: 2, index: 3, fieldCount: 3, grantSetSize: 3 });
    const grants = (record.consent as any).grants as any[];
    for (const field of Object.keys(record.fields)) {
      const matched = grants.some(
        (g) =>
          g.field === field &&
          g.purpose === record.declaredPurpose &&
          g.recipientClass === record.declaredRecipientClass &&
          g.revokedAt === null &&
          g.expiresAt === null
      );
      expect(matched).toBe(true);
    }
  });

  it('is deterministic for the same seed and index', () => {
    const a = generateSyntheticRecord({ seed: 9, index: 4, fieldCount: 2, grantSetSize: 2 });
    const b = generateSyntheticRecord({ seed: 9, index: 4, fieldCount: 2, grantSetSize: 2 });
    expect(a).toEqual(b);
  });

  it('produces a distinct _id per index for the same seed', () => {
    const a = generateSyntheticRecord({ seed: 9, index: 0, fieldCount: 1, grantSetSize: 1 });
    const b = generateSyntheticRecord({ seed: 9, index: 1, fieldCount: 1, grantSetSize: 1 });
    expect(a._id).not.toBe(b._id);
  });

  it('carries a signature that verifies against its own devicePublicKey', () => {
    const record = generateSyntheticRecord({ seed: 3, index: 0, fieldCount: 2, grantSetSize: 2 });
    const message = utf8Encode(signedPayload(record));
    const signature = base64Decode(record.signature);
    const publicKey = base64Decode(record.devicePublicKey);
    expect(ed.verify(signature, message, publicKey)).toBe(true);
  });
});
