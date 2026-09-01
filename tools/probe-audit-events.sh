#!/usr/bin/env bash
# Gate 0: proves EventRouter delivers real negotiation/transfer events and that a
# JAX-RS resource registered outside EDC's own management-api module is reachable.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-audit-probe
rm -rf "$WORK" && mkdir -p "$WORK"

cat > "$WORK/policy.json" <<'JSON'
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-policy",
  "policy": {
    "@context": [ "http://www.w3.org/ns/odrl.jsonld" ],
    "@type": "Set",
    "permission": [ { "action": "use" } ],
    "prohibition": [], "obligation": []
  }
}
JSON

trap 'stop_all' EXIT
stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

echo "--- probing audit web context ---"
curl -sf "http://localhost:19197/audit/probe"
echo
[ "$(curl -sf "http://localhost:19197/audit/probe")" = "AUDIT_PROBE_OK" ] \
  || { echo "audit probe resource unreachable" >&2; exit 1; }

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-probe-asset",
  "properties": { "name": "audit probe asset" },
  "dataAddress": { "type": "test" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-probe-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate "$WORK/policy.json")
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

echo "--- provider log lines mentioning AUDIT_PROBE ---"
grep "AUDIT_PROBE" /tmp/provider.log || { echo "no AUDIT_PROBE lines found" >&2; exit 1; }
echo "gate 0 probe passed"
