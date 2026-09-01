import { ConsentGrant, ContributionRecord, Decision } from './types';

export function evaluateRecord(record: ContributionRecord, nowIso: string): Decision {
  if (!record || record.type !== 'contribution') {
    return { allowed: true, reason: 'not-a-contribution' };
  }

  var consent = record.consent;
  if (!consent || consent.grants == null) {
    return { allowed: false, reason: 'missing-consent-assertion' };
  }

  if (consent.participantRef !== record.participantRef) {
    return { allowed: false, reason: 'participant-mismatch' };
  }

  if (record.fields !== undefined && record.fields !== null &&
      (typeof record.fields !== 'object' || Array.isArray(record.fields))) {
    return { allowed: false, reason: 'malformed-fields' };
  }

  var fields = record.fields || {};
  var names: string[] = [];
  for (var key in fields) {
    if (Object.prototype.hasOwnProperty.call(fields, key)) {
      names.push(key);
    }
  }
  names.sort();

  for (var i = 0; i < names.length; i++) {
    if (!hasGrant(consent.grants, names[i], record.declaredPurpose, record.declaredRecipientClass, nowIso)) {
      return { allowed: false, reason: 'no-grant:' + names[i] };
    }
  }

  return { allowed: true, reason: 'ok' };
}

function hasGrant(
  grants: ConsentGrant[],
  field: string,
  purpose: string,
  recipientClass: string,
  nowIso: string
): boolean {
  for (var i = 0; i < grants.length; i++) {
    var g = grants[i];
    if (g.field !== field) { continue; }
    if (g.purpose !== purpose) { continue; }
    if (g.recipientClass !== recipientClass) { continue; }
    if (g.grantedAt > nowIso) { continue; }
    if (g.expiresAt !== null && g.expiresAt <= nowIso) { continue; }
    if (g.revokedAt !== null && g.revokedAt <= nowIso) { continue; }
    return true;
  }
  return false;
}
