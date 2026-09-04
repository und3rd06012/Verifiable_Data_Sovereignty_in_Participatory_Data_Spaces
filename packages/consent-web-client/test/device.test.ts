import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ParticipantClient } from '../src/device';

function grant(field: string, purpose = 'service-improvement', recipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z',
    expiresAt: null,
    revokedAt: null
  };
}

describe('ParticipantClient', () => {
  let client: ParticipantClient;

  // ParticipantClient (unlike device-sim's Device) uses a STABLE local
  // database name keyed only by participantRef, since a real participant's
  // IndexedDB database must persist across sessions, so 'p-001' resolves
  // to the same underlying database on every construction. Explicitly
  // destroying it after each test keeps these otherwise-independent test
  // cases from accumulating each other's records.
  beforeEach(() => {
    client = new ParticipantClient('p-001');
  });

  afterEach(async () => {
    await client.localDb().destroy();
  });

  it('starts with no grants and no unsent records', async () => {
    expect(client.grants()).toEqual([]);
    expect(await client.unsentRecords()).toEqual([]);
  });

  it('queues a contributed record as unsent', async () => {
    await client.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    const unsent = await client.unsentRecords();
    expect(unsent).toHaveLength(1);
    expect(unsent[0].fields['service.satisfaction']).toBe(4);
  });

  it('revoke() stamps revokedAt on the matching local grant', async () => {
    await client.grantConsent(grant('service.satisfaction'));
    await client.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    const revoked = client.grants().find((g) => g.field === 'service.satisfaction');
    expect(revoked?.revokedAt).toBe('2026-08-30T12:00:00.000Z');
  });

  it('revoke sweeps unsent records whose embedded consent covers the revoked grant', async () => {
    await client.grantConsent(grant('service.satisfaction'));
    await client.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    const { sweptCount } = await client.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');

    expect(sweptCount).toBe(1);
    expect(await client.unsentRecords()).toEqual([]);
  });

  it('markSent survives a fresh ParticipantClient pointed at the same local db', async () => {
    await client.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    const [sent] = await client.unsentRecords();
    await client.markSent([sent._id!]);

    // A fresh instance simulates a page reload: same participantRef, so
    // the same underlying (idb-backed) local database, but a brand new
    // in-memory sentIds cache.
    const reloaded = new ParticipantClient('p-001');
    expect(await reloaded.unsentRecords()).toEqual([]);
  });

  it('revoke does not sweep a record whose fields do not include the revoked grant', async () => {
    await client.grantConsent(grant('service.satisfaction'));
    await client.grantConsent(grant('service.other'));
    await client.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.other': 1 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.other')] }
    });

    const { sweptCount } = await client.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');

    expect(sweptCount).toBe(0);
    expect(await client.unsentRecords()).toHaveLength(1);
  });
});
