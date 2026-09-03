import { Device, DutyCycle, syncDevice, resolveConflicts, injectConflict, createRemoteDb } from '@benefits/device-sim';
import type { RecipientClass } from '@benefits/consent-core';
import { mulberry32 } from './rng';

const MAX_TICKS = 200;
const REVOCATION_COUNT = 3;
const CONFLICT_COUNT = 3;
const BASELINE_COUNT = 4;
const FLEET_SIZE = REVOCATION_COUNT + CONFLICT_COUNT + BASELINE_COUNT;

// recipientClass is typed explicitly as RecipientClass rather than inferred
// as `string`: this file is compiled by tsc (unlike the test files), and an
// inferred `string` return type is not assignable to ConsentGrant.
function grant(field: string, purpose = 'service-improvement', recipientClass: RecipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
  };
}

export interface TrialResult {
  propagationTicks: number[];
  consistencyTicks: number[];
  leakedRecordCount: number;
  unresolvedConflictCount: number;
}

type Role = 'revocation' | 'conflict' | 'baseline';

interface RoleDevice {
  participantRef: string;
  device: Device;
  role: Role;
  cycle: DutyCycle;
  done: boolean;
  docId?: string;
}

export async function runTrial(
  seed: number,
  onlineProbability: number,
  onlineProbabilityIndex: number,
  trialIndex: number,
  remoteUrl: string
): Promise<TrialResult> {
  const rng = mulberry32(seed * 1_000_003 + onlineProbabilityIndex * 1_000 + trialIndex);
  const nowIso = '2026-08-30T12:00:00.000Z';

  const devices: RoleDevice[] = [];
  for (let i = 0; i < FLEET_SIZE; i++) {
    const role: Role = i < REVOCATION_COUNT ? 'revocation'
      : i < REVOCATION_COUNT + CONFLICT_COUNT ? 'conflict' : 'baseline';
    const participantRef = `p-e2-${seed}-${onlineProbabilityIndex}-${trialIndex}-${i}`;
    const device = new Device(participantRef);
    await device.grantConsent(grant('service.satisfaction'));
    devices.push({ participantRef, device, role, cycle: new DutyCycle(onlineProbability, rng), done: false });
  }

  const revocationDevices = devices.filter((d) => d.role === 'revocation');
  const conflictDevices = devices.filter((d) => d.role === 'conflict');
  const baselineDevices = devices.filter((d) => d.role === 'baseline');

  for (const entry of revocationDevices) {
    await entry.device.contribute({
      type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await entry.device.revoke('service.satisfaction', 'service-improvement', 'statistics', nowIso);
  }

  for (const entry of conflictDevices) {
    entry.docId = `e2-conflict-${entry.participantRef}`;
    await entry.device.localDb().put({
      _id: entry.docId, type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 5 }, localEditedAt: nowIso,
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await injectConflict(remoteUrl, entry.docId, {
      type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 1 }, localEditedAt: '2026-08-30T15:00:00.000Z',
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
  }

  for (const entry of baselineDevices) {
    await entry.device.contribute({
      type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 3 },
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
  }

  const propagationTicks: number[] = [];
  const consistencyTicks: number[] = [];

  for (let tick = 1; tick <= MAX_TICKS; tick++) {
    for (const entry of devices) {
      if (!entry.done) {
        entry.cycle.tick();
      }
    }
    for (const entry of devices) {
      if (entry.done || !entry.cycle.isOnline()) {
        continue;
      }
      if (entry.role === 'conflict') {
        // syncDevice must run before resolveConflicts: injectConflict (tick 0)
        // only wrote the rival revision to remote, since the device's own
        // local edit was never pushed. Without this sync, remote holds a
        // single, unconflicted leaf (the rival's), resolveConflicts finds no
        // _conflicts entry, and resolvedCount is silently 0 rather than 1
        // (confirmed live in Task 2's own spike, which hit exactly this and
        // required the same fix, matching fleet.integration.test.ts's own
        // proven "every device syncs, then resolveConflicts runs separately"
        // sequence). Modeled as one atomic action on the device's first
        // online tick, the same as every other role's single action per tick.
        await syncDevice(entry.device, remoteUrl);
        await resolveConflicts(entry.device, remoteUrl);
        consistencyTicks.push(tick);
      } else {
        await syncDevice(entry.device, remoteUrl);
        if (entry.role === 'revocation') {
          propagationTicks.push(tick);
        }
      }
      entry.done = true;
    }
    if (devices.every((d) => d.done)) {
      break;
    }
  }

  const notDone = devices.filter((d) => !d.done);
  if (notDone.length > 0) {
    throw new Error(
      `${notDone.length} device(s) never came online within ${MAX_TICKS} ticks at onlineProbability=${onlineProbability}`
    );
  }

  const remote = createRemoteDb(remoteUrl);
  const allDocs = await remote.allDocs({ include_docs: true });
  let leakedRecordCount = 0;
  for (const entry of revocationDevices) {
    const leaked = allDocs.rows.filter((row: any) =>
      row.doc?.participantRef === entry.participantRef && row.doc?.declaredPurpose === 'service-improvement'
    );
    leakedRecordCount += leaked.length;
  }

  let unresolvedConflictCount = 0;
  for (const entry of conflictDevices) {
    const doc = await remote.get(entry.docId!, { conflicts: true }) as any;
    if ((doc._conflicts ?? []).length > 0) {
      unresolvedConflictCount++;
    }
  }

  return { propagationTicks, consistencyTicks, leakedRecordCount, unresolvedConflictCount };
}
