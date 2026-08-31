import { describe, it, expect } from 'vitest';
import { evaluateRecord } from '../src/evaluate';
import { ContributionRecord } from '../src/types';

function record(overrides: Partial<ContributionRecord> = {}): ContributionRecord {
  return {
    type: 'contribution',
    participantRef: 'p-001',
    siteId: 'athens',
    collectedAt: '2026-08-30T11:00:00.000Z',
    channel: 'app',
    declaredPurpose: 'service-improvement',
    declaredRecipientClass: 'statistics',
    fields: { 'service.satisfaction': 4 },
    consent: {
      participantRef: 'p-001',
      assertedAt: '2026-08-01T00:00:00.000Z',
      grants: [
        {
          field: 'service.satisfaction',
          purpose: 'service-improvement',
          recipientClass: 'statistics',
          grantedAt: '2026-08-01T00:00:00.000Z',
          expiresAt: null
        }
      ]
    },
    ...overrides
  };
}

describe('evaluateRecord boundaries', () => {
  it('treats expiry as exclusive at the exact instant', () => {
    const r = record();
    r.consent.grants[0].expiresAt = '2026-08-30T12:00:00.000Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(false);
  });

  it('allows one millisecond before expiry', () => {
    const r = record();
    r.consent.grants[0].expiresAt = '2026-08-30T12:00:00.001Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(true);
  });

  it('rejects a grant that is not yet active', () => {
    const r = record();
    r.consent.grants[0].grantedAt = '2026-09-01T00:00:00.000Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(false);
  });

  it('allows a record with no fields at all', () => {
    const r = record({ fields: {} });
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z')).toEqual({ allowed: true, reason: 'ok' });
  });

  it('reports the alphabetically first ungranted field', () => {
    const r = record({ fields: { 'z.field': 1, 'a.field': 2 } });
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').reason).toBe('no-grant:a.field');
  });
});
