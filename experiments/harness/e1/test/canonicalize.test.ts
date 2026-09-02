import { describe, it, expect } from 'vitest';
import { canonicalize, signedPayload } from '../src/canonicalize';

describe('canonicalize', () => {
  it('sorts top-level keys regardless of insertion order', () => {
    expect(canonicalize({ b: 1, a: 2 })).toBe('{"a":2,"b":1}');
  });

  it('sorts nested object keys', () => {
    expect(canonicalize({ z: { d: 1, c: 2 }, a: 1 })).toBe('{"a":1,"z":{"c":2,"d":1}}');
  });

  it('preserves array order without sorting array elements', () => {
    expect(canonicalize({ a: [3, 1, 2] })).toBe('{"a":[3,1,2]}');
  });

  it('produces identical output for two differently-ordered equivalent objects', () => {
    const one = canonicalize({ fields: { x: 1, y: 2 }, participantRef: 'p-1' });
    const two = canonicalize({ participantRef: 'p-1', fields: { y: 2, x: 1 } });
    expect(one).toBe(two);
  });
});

describe('signedPayload', () => {
  it('includes only participant-authored fields, excluding _id, _rev, devicePublicKey, signature', () => {
    const record = {
      _id: 'contribution-1',
      _rev: '1-abc',
      participantRef: 'p-1',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] },
      devicePublicKey: 'ignored-in-payload',
      signature: 'ignored-in-payload'
    };
    const payload = signedPayload(record);
    expect(payload).not.toContain('_id');
    expect(payload).not.toContain('_rev');
    expect(payload).not.toContain('devicePublicKey');
    expect(payload).not.toContain('ignored-in-payload');
    expect(payload).toContain('participantRef');
    expect(payload).toContain('service.satisfaction');
  });

  it('is stable under a differently-ordered but equal input object', () => {
    const base = {
      participantRef: 'p-1',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { a: 1, b: 2 },
      consent: { participantRef: 'p-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] }
    };
    const reordered = {
      consent: base.consent,
      fields: { b: 2, a: 1 },
      declaredRecipientClass: base.declaredRecipientClass,
      declaredPurpose: base.declaredPurpose,
      channel: base.channel,
      collectedAt: base.collectedAt,
      siteId: base.siteId,
      participantRef: base.participantRef
    };
    expect(signedPayload(base)).toBe(signedPayload(reordered));
  });
});
