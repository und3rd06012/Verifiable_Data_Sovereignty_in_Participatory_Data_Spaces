export function canonicalize(value: unknown): string {
  return JSON.stringify(sortKeysDeep(value));
}

function sortKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) {
    var result: unknown[] = [];
    for (var i = 0; i < value.length; i++) {
      result.push(sortKeysDeep(value[i]));
    }
    return result;
  }
  if (value !== null && typeof value === 'object') {
    var keys = Object.keys(value as Record<string, unknown>).sort();
    var sorted: Record<string, unknown> = {};
    for (var j = 0; j < keys.length; j++) {
      sorted[keys[j]] = sortKeysDeep((value as Record<string, unknown>)[keys[j]]);
    }
    return sorted;
  }
  return value;
}

export interface SignableRecord {
  participantRef: string;
  siteId: string;
  collectedAt: string;
  channel: string;
  declaredPurpose: string;
  declaredRecipientClass: string;
  fields: Record<string, unknown>;
  consent: unknown;
}

export function signedPayload(record: SignableRecord): string {
  return canonicalize({
    participantRef: record.participantRef,
    siteId: record.siteId,
    collectedAt: record.collectedAt,
    channel: record.channel,
    declaredPurpose: record.declaredPurpose,
    declaredRecipientClass: record.declaredRecipientClass,
    fields: record.fields,
    consent: record.consent
  });
}
