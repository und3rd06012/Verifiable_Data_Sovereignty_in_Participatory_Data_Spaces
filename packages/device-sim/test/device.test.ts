import { describe, it, expect, beforeEach } from 'vitest';
import { Device } from '../src/device';

function grant(field: string, purpose = 'service-improvement', recipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z',
    expiresAt: null,
    revokedAt: null
  };
}

describe('Device', () => {
  let device: Device;

  beforeEach(() => {
    device = new Device('p-001');
  });

  it('starts with no grants and no unsent records', async () => {
    expect(device.grants()).toEqual([]);
    expect(await device.unsentRecords()).toEqual([]);
  });

  it('queues a contributed record as unsent', async () => {
    await device.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    const unsent = await device.unsentRecords();
    expect(unsent).toHaveLength(1);
    expect(unsent[0].fields['service.satisfaction']).toBe(4);
  });

  it('revoke() stamps revokedAt on the matching local grant', async () => {
    await device.grantConsent(grant('service.satisfaction'));
    await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    const revoked = device.grants().find((g) => g.field === 'service.satisfaction');
    expect(revoked?.revokedAt).toBe('2026-08-30T12:00:00.000Z');
  });

  it('revoke sweeps unsent records whose embedded consent covers the revoked grant', async () => {
    await device.grantConsent(grant('service.satisfaction'));
    await device.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    const { sweptCount } = await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');

    expect(sweptCount).toBe(1);
    expect(await device.unsentRecords()).toEqual([]);
  });

  it('revoke does not sweep a record whose fields do not include the revoked grant', async () => {
    await device.grantConsent(grant('service.satisfaction'));
    await device.grantConsent(grant('service.other'));
    await device.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.other': 1 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.other')] }
    });

    const { sweptCount } = await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');

    expect(sweptCount).toBe(0);
    expect(await device.unsentRecords()).toHaveLength(1);
  });
});
