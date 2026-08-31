"""Check that one C1 case was refused, or accepted, for the reason the case exists to test.

Reads the artifacts the suite keeps per case: the provider log, the final negotiation
document, the requester properties the consumer ran with, and the policy that was published.
Prints one evidence line and exits non-zero if the evidence is not there.
"""
import json
import re
import sys

case = sys.argv[1]
log = open('/tmp/provider-%s.log' % case, errors='replace').read()
negotiation = json.load(open('/tmp/negotiation-%s.json' % case))
properties = dict(
    line.strip().split('=', 1)
    for line in open('/tmp/consumer-%s.properties' % case)
    if '=' in line and not line.startswith('#')
)
policy = json.load(open('/tmp/policy-%s.json' % case))['policy']
constraints = {c['leftOperand']: c['rightOperand'] for c in policy['permission'][0]['constraint']}

quorum = re.search(
    r'steward quorum check releaseRequest=rr-%s signed=(\d+) required=(\d+)' % re.escape(case), log)
refused = 'Policy in scope contract.negotiation not fulfilled' in (negotiation.get('errorDetail') or '')

granted = constraints['benefits:fieldSet']
granted = granted if isinstance(granted, list) else [granted]
requested = [f for f in properties.get('benefits.requester.fields', '').split(',') if f]

evidence = []
ok = True


def require(condition, text):
    global ok
    if not condition:
        ok = False
    evidence.append(('' if condition else 'MISSING: ') + text)


if case == 'quorum-met':
    require(negotiation.get('state') == 'FINALIZED', 'negotiation finalized')
    require(bool(quorum) and quorum.groups() == ('2', '2'),
            'quorum satisfied, provider logged signed=2 required=2')
else:
    require(refused, 'refused by policy evaluation in scope contract.negotiation')

if case == 'quorum-missing':
    require(quorum is None,
            'quorum function returned before its check log, the store held no authorization for rr-quorum-missing')
    require('seeded' not in log, 'no steward authorization was seeded')
elif case == 'quorum-one-steward-twice':
    require(bool(quorum) and quorum.groups() == ('1', '2'),
            'two signatures from one steward counted as signed=1 required=2')
elif case == 'purpose-mismatch':
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.purpose'] != constraints['benefits:purpose'],
            'requester purpose %s against policy purpose %s' % (
                properties['benefits.requester.purpose'], constraints['benefits:purpose']))
    require(properties['benefits.requester.recipientClass'] == constraints['benefits:recipientClass'],
            'recipient class matches, so purpose is the only failing claim')
    require(set(requested) <= set(granted), 'requested fields are granted, so purpose is isolated')
elif case == 'recipient-class-mismatch':
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.recipientClass'] != constraints['benefits:recipientClass'],
            'requester recipient class %s against policy recipient class %s' % (
                properties['benefits.requester.recipientClass'], constraints['benefits:recipientClass']))
    require(properties['benefits.requester.purpose'] == constraints['benefits:purpose'],
            'purpose matches, so recipient class is the only failing claim')
    require(set(requested) <= set(granted), 'requested fields are granted, so recipient class is isolated')
elif case in ('field-outside-grant', 'expired-grant-field-excluded'):
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.purpose'] == constraints['benefits:purpose'],
            'purpose matches')
    require(properties['benefits.requester.recipientClass'] == constraints['benefits:recipientClass'],
            'recipient class matches')
    require(not set(requested) <= set(granted),
            'requested %s outside granted %s' % (','.join(requested), ','.join(granted)))
    if case == 'expired-grant-field-excluded':
        require('health.condition' not in granted,
                'the expired health.condition grant was excluded from the compiled policy')

print('; '.join(evidence))
sys.exit(0 if ok else 1)
