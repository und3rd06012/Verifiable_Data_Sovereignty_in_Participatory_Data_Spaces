import { ConsentGrant } from '@benefits/consent-core';

export const BENEFITS_NS = 'https://w3id.org/benefits/v1/ns/';

export interface CompileOptions {
  releaseRequestId: string;
  quorum: number;
  kAnonymity: number;
  epsilon: number;
  /** The instant the policy is compiled at. Grants already expired are excluded. */
  at: string;
}

interface AtomicConstraint {
  '@type': 'AtomicConstraint';
  leftOperand: string;
  operator: string;
  rightOperand: unknown;
}

function constraint(leftOperand: string, operator: string, rightOperand: unknown): AtomicConstraint {
  return { '@type': 'AtomicConstraint', leftOperand, operator, rightOperand };
}

function unique(values: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < values.length; i++) {
    if (out.indexOf(values[i]) === -1) {
      out.push(values[i]);
    }
  }
  return out.sort();
}

export function compilePolicy(all: ConsentGrant[], options: CompileOptions) {
  const grants = all.filter((g) => g.expiresAt === null || g.expiresAt > options.at);

  if (grants.length === 0) {
    throw new Error('cannot compile a policy from an empty consent rule set');
  }

  const recipientClasses = unique(grants.map((g) => g.recipientClass));
  if (recipientClasses.length !== 1) {
    throw new Error(
      'a release policy must target a single recipient class, got: ' + recipientClasses.join(', ')
    );
  }

  const purposes = unique(grants.map((g) => g.purpose));
  if (purposes.length !== 1) {
    throw new Error(
      'a release policy must target a single purpose, got: ' + purposes.join(', ')
    );
  }

  return {
    '@context': ['http://www.w3.org/ns/odrl.jsonld', { benefits: BENEFITS_NS }],
    '@type': 'Set',
    permission: [
      {
        action: 'use',
        constraint: [
          constraint('benefits:releaseRequest', 'eq', options.releaseRequestId),
          // EDC's management API validator reads odrl:rightOperand as a JSON string and throws
          // ClassCastException on a JSON number, so a numeric right operand is emitted in its
          // string form. The value never changes, only its JSON type, and the connector's
          // stewardQuorum function parses the integer back out of the string.
          constraint('benefits:stewardQuorum', 'gteq', String(options.quorum)),
          constraint('benefits:purpose', 'eq', purposes[0]),
          constraint('benefits:recipientClass', 'eq', recipientClasses[0]),
          constraint('benefits:fieldSet', 'isPartOf', unique(grants.map((g) => g.field))),
          // benefits:kAnonymity and benefits:epsilonBudget are emitted here because the connector
          // now has registered constraint functions for both, with scope bindings, so EDC accepts
          // the policy definition instead of rejecting it with HTTP 400.
          constraint('benefits:kAnonymity', 'gteq', String(options.kAnonymity)),
          constraint('benefits:epsilonBudget', 'lteq', String(options.epsilon))
        ]
      }
    ],
    prohibition: [],
    obligation: []
  };
}
