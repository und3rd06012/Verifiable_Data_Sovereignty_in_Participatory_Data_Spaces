import { mulberry32 } from './rng.js';
import { generateKeyPair, signRecord } from './sign.js';
import { SignableRecord } from './canonicalize.js';

export interface SyntheticRecord extends SignableRecord {
  _id: string;
  type: 'contribution';
  devicePublicKey: string;
  signature: string;
}

export interface SyntheticRecordOptions {
  seed: number;
  index: number;
  fieldCount: number;
  decoyCount: number;
}

// mulberry32(seed) alone would replay the same sequence for every record at a
// given seed. Folding index into the seed via a fixed multiplier keeps records
// distinct while staying fully reproducible from (seed, index) alone.
function seedFor(seed: number, index: number): number {
  return (seed * 2654435761 + index) >>> 0;
}

export function generateSyntheticRecord(opts: SyntheticRecordOptions): SyntheticRecord {
  const rand = mulberry32(seedFor(opts.seed, opts.index));
  const participantRef = 'synthetic-participant-' + (opts.seed % 1000) + '-' + (opts.index % 500);

  const fields: Record<string, unknown> = {};
  const grantedFieldNames: string[] = [];
  for (let i = 0; i < opts.fieldCount; i++) {
    const fieldName = 'synthetic.field' + i;
    fields[fieldName] = Math.floor(rand() * 100);
    grantedFieldNames.push(fieldName);
  }

  const grants: unknown[] = [];
  // Decoys first: hasGrant() scans the array in order and returns on the
  // first match, so every field's lookup must walk past all decoys before
  // reaching its own grant. This is what makes decoyCount the parameter
  // that actually drives evaluateRecord's scan cost (see spec 3.2).
  for (let i = 0; i < opts.decoyCount; i++) {
    grants.push({
      field: 'synthetic.decoy' + i,
      purpose: 'service-improvement',
      recipientClass: 'statistics',
      grantedAt: '2026-01-01T00:00:00.000Z',
      expiresAt: null,
      revokedAt: null
    });
  }
  for (const field of grantedFieldNames) {
    grants.push({
      field,
      purpose: 'service-improvement',
      recipientClass: 'statistics',
      grantedAt: '2026-01-01T00:00:00.000Z',
      expiresAt: null,
      revokedAt: null
    });
  }

  const record: SignableRecord = {
    participantRef,
    siteId: 'synthetic-site',
    collectedAt: '2026-08-30T11:00:00.000Z',
    channel: 'app',
    declaredPurpose: 'service-improvement',
    declaredRecipientClass: 'statistics',
    fields,
    consent: { participantRef, assertedAt: '2026-01-01T00:00:00.000Z', grants }
  };

  const keyPair = generateKeyPair(rand);
  const signature = signRecord(record, keyPair.privateKey);

  return {
    ...record,
    _id: 'synthetic-contribution-' + opts.seed + '-' + opts.index,
    type: 'contribution',
    devicePublicKey: keyPair.publicKeyBase64,
    signature
  };
}
