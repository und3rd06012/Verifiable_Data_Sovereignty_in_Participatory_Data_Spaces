import { compilePolicy } from '../packages/consent-odrl/dist/compile.js';

const AT = '2026-08-30T12:00:00.000Z';

function grant(field, expiresAt = null) {
  return {
    field,
    purpose: 'service-improvement',
    recipientClass: 'statistics',
    grantedAt: '2026-08-01T00:00:00.000Z',
    expiresAt,
    revokedAt: null
  };
}

const CASES = {
  'quorum-met': [grant('service.satisfaction')],
  'quorum-missing': [grant('service.satisfaction')],
  'quorum-one-steward-twice': [grant('service.satisfaction')],
  'purpose-mismatch': [grant('service.satisfaction')],
  'recipient-class-mismatch': [grant('service.satisfaction')],
  'field-outside-grant': [grant('service.satisfaction')],
  'expired-grant-field-excluded': [
    grant('service.satisfaction'),
    grant('health.condition', '2026-08-15T00:00:00.000Z')
  ],
  'k-below-threshold': [grant('service.satisfaction')],
  'budget-exceeded': [grant('service.satisfaction')]
};

const name = process.argv[2];
const grants = CASES[name];
if (!grants) {
  console.error('unknown case: ' + name);
  process.exit(2);
}

const policy = compilePolicy(grants, {
  releaseRequestId: 'rr-' + name,
  quorum: 2,
  kAnonymity: 5,
  epsilon: 0.5,
  at: AT
});

process.stdout.write(JSON.stringify({
  '@context': { '@vocab': 'https://w3id.org/edc/v0.0.1/ns/' },
  '@id': 'benefits-policy',
  policy
}, null, 2));
