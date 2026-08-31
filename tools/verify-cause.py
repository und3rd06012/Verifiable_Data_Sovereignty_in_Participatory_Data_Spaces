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
artifact = json.load(open('/tmp/artifacts-%s.json' % case))[0]
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
    require('steward authorizations' not in log, 'no steward authorization was seeded')
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
elif case == 'k-below-threshold':
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.purpose'] == constraints['benefits:purpose'],
            'purpose matches')
    require(properties['benefits.requester.recipientClass'] == constraints['benefits:recipientClass'],
            'recipient class matches')
    require(all(f in granted for f in requested), 'requested fields are granted')
    require(float(artifact['epsilonCharged']) <= float(constraints['benefits:epsilonBudget']),
            'epsilon charged %s within policy cap %s, so budget is not the cause' % (
                artifact['epsilonCharged'], constraints['benefits:epsilonBudget']))
    require(int(artifact['computedK']) < int(constraints['benefits:kAnonymity']),
            'artifact computed k %s below policy threshold %s, so k is the only failing constraint' % (
                artifact['computedK'], constraints['benefits:kAnonymity']))
    k_log = re.search(
        r'kAnonymity check releaseRequest=rr-%s computedK=(\d+) required=(\d+)' % re.escape(case), log)
    require(bool(k_log) and k_log.groups() == (str(artifact['computedK']), str(constraints['benefits:kAnonymity'])),
            'KAnonymityFunction logged computedK=%s required=%s' % (
                artifact['computedK'], constraints['benefits:kAnonymity']))
elif case == 'budget-exceeded':
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.purpose'] == constraints['benefits:purpose'],
            'purpose matches')
    require(properties['benefits.requester.recipientClass'] == constraints['benefits:recipientClass'],
            'recipient class matches')
    require(all(f in granted for f in requested), 'requested fields are granted')
    require(int(artifact['computedK']) >= int(constraints['benefits:kAnonymity']),
            'artifact computed k %s meets policy threshold %s, so k is not the cause' % (
                artifact['computedK'], constraints['benefits:kAnonymity']))
    require(float(artifact['epsilonCharged']) > float(constraints['benefits:epsilonBudget']),
            'artifact charged epsilon %s above policy cap %s, so budget is the only failing constraint' % (
                artifact['epsilonCharged'], constraints['benefits:epsilonBudget']))
    eps_log = re.search(
        r'epsilonBudget check releaseRequest=rr-%s charged=([0-9.]+) cap=([0-9.]+)' % re.escape(case), log)
    require(bool(eps_log) and eps_log.groups() == (
                str(artifact['epsilonCharged']), str(constraints['benefits:epsilonBudget'])),
            'EpsilonBudgetFunction logged charged=%s cap=%s' % (
                artifact['epsilonCharged'], constraints['benefits:epsilonBudget']))

print('; '.join(evidence))
sys.exit(0 if ok else 1)
