import { describe, it, expect } from 'vitest';
import { mulberry32 } from '../src/rng';
import { generateKeyPair, signRecord } from '../src/sign';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';
import { base64Decode, utf8Encode } from '../src/portable-codec';
import { signedPayload } from '../src/canonicalize';

ed.hashes.sha512 = sha512;

const record = {
  participantRef: 'p-1',
  siteId: 'athens',
  collectedAt: '2026-08-30T11:00:00.000Z',
  channel: 'app',
  declaredPurpose: 'service-improvement',
  declaredRecipientClass: 'statistics',
  fields: { 'service.satisfaction': 4 },
  consent: { participantRef: 'p-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] }
};

describe('generateKeyPair', () => {
  it('produces a deterministic key pair for a fixed seed', () => {
    const a = generateKeyPair(mulberry32(5));
    const b = generateKeyPair(mulberry32(5));
    expect(a.publicKeyBase64).toBe(b.publicKeyBase64);
  });

  it('produces different key pairs for different seeds', () => {
    const a = generateKeyPair(mulberry32(5));
    const b = generateKeyPair(mulberry32(6));
    expect(a.publicKeyBase64).not.toBe(b.publicKeyBase64);
  });
});

describe('signRecord', () => {
  it('produces a signature that verifies against the matching public key using @noble/ed25519 directly', () => {
    const keyPair = generateKeyPair(mulberry32(11));
    const signatureBase64 = signRecord(record, keyPair.privateKey);
    const message = utf8Encode(signedPayload(record));
    const signature = base64Decode(signatureBase64);
    const publicKey = base64Decode(keyPair.publicKeyBase64);
    expect(ed.verify(signature, message, publicKey)).toBe(true);
  });

  it('produces a signature that fails against a tampered payload', () => {
    const keyPair = generateKeyPair(mulberry32(11));
    const signatureBase64 = signRecord(record, keyPair.privateKey);
    const tampered = { ...record, fields: { 'service.satisfaction': 1 } };
    const message = utf8Encode(signedPayload(tampered));
    const signature = base64Decode(signatureBase64);
    const publicKey = base64Decode(keyPair.publicKeyBase64);
    expect(ed.verify(signature, message, publicKey)).toBe(false);
  });
});
