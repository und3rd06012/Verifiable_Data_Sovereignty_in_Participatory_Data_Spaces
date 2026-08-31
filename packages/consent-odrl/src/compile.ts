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

  return {
    '@context': ['http://www.w3.org/ns/odrl.jsonld', { benefits: BENEFITS_NS }],
    '@type': 'Set',
    permission: [
      {
        action: 'use',
        constraint: [
          constraint('benefits:releaseRequest', 'eq', options.releaseRequestId),
          constraint('benefits:stewardQuorum', 'gteq', options.quorum),
          constraint('benefits:purpose', 'isPartOf', unique(grants.map((g) => g.purpose))),
          constraint('benefits:recipientClass', 'eq', recipientClasses[0]),
          constraint('benefits:fieldSet', 'isPartOf', unique(grants.map((g) => g.field))),
          constraint('benefits:kAnonymity', 'gteq', options.kAnonymity),
          constraint('benefits:epsilonBudget', 'lteq', options.epsilon)
        ]
      }
    ],
    prohibition: [],
    obligation: []
  };
}
