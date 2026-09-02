import { mulberry32 } from './rng';
import { generateKeyPair, signRecord } from './sign';
import { SignableRecord } from './canonicalize';

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
  grantSetSize: number;
}

// mulberry32(seed) alone would replay the same sequence for every record at a
// given seed. Folding index into the seed via a fixed multiplier keeps records
// distinct while staying fully reproducible from (seed, index) alone.
function seedFor(seed: number, index: number): number {
  return (seed * 2654435761 + index) >>> 0;
}

/**
 * Generate a fully-consented synthetic ContributionRecord with signed device identity.
 *
 * When fieldCount > grantSetSize, the realized consent.grants.length will be
 * Math.max(fieldCount, grantSetSize), not grantSetSize. Every field in the record
 * must have its own matching ConsentGrant (one grant covers exactly one field), so
 * to satisfy the full-consent guarantee this function provides, additional grants
 * are automatically created for any fields not covered by grantSetSize alone.
 *
 * Callers requiring the actual grant count should read record.consent.grants.length
 * directly rather than assuming it equals opts.grantSetSize.
 */
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
  for (let i = 0; i < opts.grantSetSize; i++) {
    // Wrap so that with grantSetSize < fieldCount, at least the first fields are
    // still covered; with grantSetSize >= fieldCount, every field is covered and
    // the remaining grants cover synthetic filler fields the record never uses.
    const field = i < grantedFieldNames.length ? grantedFieldNames[i] : 'synthetic.unused' + i;
    grants.push({
      field,
      purpose: 'service-improvement',
      recipientClass: 'statistics',
      grantedAt: '2026-01-01T00:00:00.000Z',
      expiresAt: null,
      revokedAt: null
    });
  }
  // If grantSetSize < fieldCount, the remaining fields need their own grants too,
  // so the record stays fully consented regardless of which dimension is larger.
  for (let i = grants.length; i < grantedFieldNames.length; i++) {
    grants.push({
      field: grantedFieldNames[i],
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
