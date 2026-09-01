import { ConsentGrant } from './types';

export function meet(a: ConsentGrant[], b: ConsentGrant[]): ConsentGrant[] {
  var out: ConsentGrant[] = [];
  for (var i = 0; i < a.length; i++) {
    for (var j = 0; j < b.length; j++) {
      var x = a[i];
      var y = b[j];
      if (x.field === y.field && x.purpose === y.purpose && x.recipientClass === y.recipientClass) {
        out.push({
          field: x.field,
          purpose: x.purpose,
          recipientClass: x.recipientClass,
          grantedAt: x.grantedAt > y.grantedAt ? x.grantedAt : y.grantedAt,
          expiresAt: earliest(x.expiresAt, y.expiresAt),
          revokedAt: earliest(x.revokedAt, y.revokedAt)
        });
      }
    }
  }
  return out;
}

export function meetAll(sets: ConsentGrant[][]): ConsentGrant[] {
  if (sets.length === 0) {
    return [];
  }
  var acc = sets[0];
  for (var i = 1; i < sets.length; i++) {
    acc = meet(acc, sets[i]);
  }
  return acc;
}

function earliest(p: string | null, q: string | null): string | null {
  if (p === null) { return q; }
  if (q === null) { return p; }
  return p < q ? p : q;
}
