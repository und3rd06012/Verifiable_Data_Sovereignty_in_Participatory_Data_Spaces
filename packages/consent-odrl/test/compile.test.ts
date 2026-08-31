import { describe, it, expect } from 'vitest';
import { compilePolicy } from '../src/compile';
import { ConsentGrant } from '@benefits/consent-core';

const AT = '2026-08-30T12:00:00.000Z';

const grants: ConsentGrant[] = [
  { field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics', grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null },
  { field: 'service.waitTime', purpose: 'service-improvement', recipientClass: 'statistics', grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null }
];

function constraintFor(policy: any, leftOperand: string) {
  return policy.permission[0].constraint.find((c: any) => c.leftOperand === leftOperand);
}

describe('compilePolicy', () => {
  const policy = compilePolicy(grants, { releaseRequestId: 'rr-42', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });

  it('declares the benefits prefix in the context', () => {
    expect(policy['@context']).toContainEqual({ benefits: 'https://w3id.org/benefits/v1/ns/' });
  });

  it('emits a single use permission', () => {
    expect(policy.permission).toHaveLength(1);
    expect(policy.permission[0].action).toBe('use');
  });

  it('carries the release request id as a sibling constraint', () => {
    expect(constraintFor(policy, 'benefits:releaseRequest')).toEqual({
      '@type': 'AtomicConstraint',
      leftOperand: 'benefits:releaseRequest',
      operator: 'eq',
      rightOperand: 'rr-42'
    });
  });

  it('emits the quorum constraint with gteq', () => {
    expect(constraintFor(policy, 'benefits:stewardQuorum')).toEqual({
      '@type': 'AtomicConstraint',
      leftOperand: 'benefits:stewardQuorum',
      operator: 'gteq',
      rightOperand: 2
    });
  });

  it('derives the purpose set from the grants', () => {
    expect(constraintFor(policy, 'benefits:purpose').rightOperand).toEqual(['service-improvement']);
  });

  it('derives the field set from the grants, sorted', () => {
    expect(constraintFor(policy, 'benefits:fieldSet').rightOperand)
      .toEqual(['service.satisfaction', 'service.waitTime']);
  });

  it('rejects an empty grant set rather than emitting an unconstrained policy', () => {
    expect(() => compilePolicy([], { releaseRequestId: 'rr-1', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT }))
      .toThrowError(/empty consent/);
  });

  it('rejects grants spanning more than one recipient class', () => {
    const mixed = [...grants, { ...grants[0], recipientClass: 'provider' as const }];
    expect(() => compilePolicy(mixed, { releaseRequestId: 'rr-1', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT }))
      .toThrowError(/single recipient class/);
  });

  it('excludes a grant that has already expired at the compile instant', () => {
    const withExpired = [...grants, {
      field: 'health.condition',
      purpose: 'service-improvement',
      recipientClass: 'statistics' as const,
      grantedAt: '2026-08-01T00:00:00.000Z',
      expiresAt: '2026-08-15T00:00:00.000Z'
    }];
    const compiled = compilePolicy(withExpired, { releaseRequestId: 'rr-9', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });
    expect(constraintFor(compiled, 'benefits:fieldSet').rightOperand).not.toContain('health.condition');
  });

  it('keeps a grant expiring one millisecond after the compile instant', () => {
    const withFuture = [{ ...grants[0], expiresAt: '2026-08-30T12:00:00.001Z' }];
    const compiled = compilePolicy(withFuture, { releaseRequestId: 'rr-10', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });
    expect(constraintFor(compiled, 'benefits:fieldSet').rightOperand).toEqual(['service.satisfaction']);
  });

  it('rejects a rule set whose every grant has expired', () => {
    const allExpired = grants.map((g) => ({ ...g, expiresAt: '2026-08-15T00:00:00.000Z' }));
    expect(() => compilePolicy(allExpired, { releaseRequestId: 'rr-11', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT }))
      .toThrowError(/empty consent/);
  });

  it('deduplicates fieldSet and purpose right operands when grants contain duplicate triples', () => {
    const duplicated: ConsentGrant[] = [
      grants[0],
      grants[0],
      { ...grants[1] }
    ];
    const compiled = compilePolicy(duplicated, { releaseRequestId: 'rr-12', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });
    expect(constraintFor(compiled, 'benefits:fieldSet').rightOperand)
      .toEqual(['service.satisfaction', 'service.waitTime']);
    expect(constraintFor(compiled, 'benefits:purpose').rightOperand)
      .toEqual(['service-improvement']);
  });

  it('deduplicates fieldSet when two distinct grants share a field but differ in nothing else', () => {
    const sharedField: ConsentGrant[] = [
      { field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics', grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null },
      { field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics', grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null }
    ];
    const compiled = compilePolicy(sharedField, { releaseRequestId: 'rr-13', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });
    expect(constraintFor(compiled, 'benefits:fieldSet').rightOperand).toEqual(['service.satisfaction']);
    expect(constraintFor(compiled, 'benefits:purpose').rightOperand).toEqual(['service-improvement']);
  });
});
