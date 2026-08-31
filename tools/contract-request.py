"""Build a contract request from the offer id in the catalog and the policy that was published.

The offer body is rebuilt from the same compiled policy the provider published rather than
echoed back from the catalog response, because the catalog is returned in EDC's compacted
form whose prefixes do not survive being re-embedded in a request document. Reusing the
compiled policy keeps the request from drifting from the published offer, which is what
matters here.
"""
import json
import sys

policy_file = sys.argv[1]
catalog_file = sys.argv[2] if len(sys.argv) > 2 else '/tmp/catalog.json'

catalog = json.load(open(catalog_file))


def find_offer(node):
    if isinstance(node, dict):
        if node.get('@type') in ('odrl:Offer', 'Offer') and '@id' in node:
            return node
        for value in node.values():
            found = find_offer(value)
            if found is not None:
                return found
    elif isinstance(node, list):
        for value in node:
            found = find_offer(value)
            if found is not None:
                return found
    return None


offer = find_offer(catalog)
if offer is None:
    sys.stderr.write('no offer in catalog\n')
    sys.exit(1)

policy = json.load(open(policy_file))['policy']
permissions = []
for permission in policy['permission']:
    permission = dict(permission)
    permission['target'] = 'benefits-aggregate'
    permissions.append(permission)

json.dump({
    '@context': {'@vocab': 'https://w3id.org/edc/v0.0.1/ns/'},
    '@type': 'ContractRequest',
    'counterPartyId': 'provider',
    'counterPartyAddress': 'http://localhost:19194/protocol/2025-1',
    'protocol': 'dataspace-protocol-http:2025-1',
    'policy': {
        '@context': policy['@context'],
        '@id': offer['@id'],
        '@type': 'Offer',
        'permission': permissions,
        'assigner': 'provider',
        'target': 'benefits-aggregate'
    }
}, sys.stdout, indent=2)
