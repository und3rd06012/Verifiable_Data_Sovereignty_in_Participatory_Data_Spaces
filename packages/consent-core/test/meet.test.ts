import { describe, it, expect } from 'vitest';
import { meet, meetAll } from '../src/meet';
import { ConsentGrant } from '../src/types';

function grant(field: string, expiresAt: string | null = null, grantedAt = '2026-08-01T00:00:00.000Z'): ConsentGrant {
  return { field, purpose: 'service-improvement', recipientClass: 'statistics', grantedAt, expiresAt };
}

describe('meet', () => {
  it('keeps only triples present in both sets', () => {
    const result = meet([grant('a'), grant('b')], [grant('b'), grant('c')]);
    expect(result.map((g) => g.field)).toEqual(['b']);
  });

  it('takes the earliest expiry', () => {
    const result = meet([grant('a', '2026-12-01T00:00:00.000Z')], [grant('a', '2026-10-01T00:00:00.000Z')]);
    expect(result[0].expiresAt).toBe('2026-10-01T00:00:00.000Z');
  });

  it('treats null expiry as unbounded', () => {
    const result = meet([grant('a', null)], [grant('a', '2026-10-01T00:00:00.000Z')]);
    expect(result[0].expiresAt).toBe('2026-10-01T00:00:00.000Z');
  });

  it('takes the latest grantedAt', () => {
    const result = meet(
      [grant('a', null, '2026-08-01T00:00:00.000Z')],
      [grant('a', null, '2026-09-01T00:00:00.000Z')]
    );
    expect(result[0].grantedAt).toBe('2026-09-01T00:00:00.000Z');
  });

  it('does not match across differing purpose', () => {
    const other = { ...grant('a'), purpose: 'advocacy' };
    expect(meet([grant('a')], [other])).toEqual([]);
  });

  it('is idempotent', () => {
    const set = [grant('a'), grant('b')];
    expect(meet(set, set)).toEqual(set);
  });

  it('is commutative in field membership', () => {
    const left = meet([grant('a'), grant('b')], [grant('b')]);
    const right = meet([grant('b')], [grant('a'), grant('b')]);
    expect(left).toEqual(right);
  });

  it('meetAll of an empty list is empty', () => {
    expect(meetAll([])).toEqual([]);
  });

  it('meetAll folds across three contributors', () => {
    const result = meetAll([[grant('a'), grant('b')], [grant('b'), grant('c')], [grant('b')]]);
    expect(result.map((g) => g.field)).toEqual(['b']);
  });
});
