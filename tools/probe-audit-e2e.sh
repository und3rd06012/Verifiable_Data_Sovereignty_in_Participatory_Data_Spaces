#!/usr/bin/env bash
# Gate 4: a real negotiation and transfer through the running connector produce a
# sealed epoch, and the TypeScript verifier confirms it from the HTTP response alone.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-audit-e2e
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
ensure_couchdb
# Wipe any prior run's audit state first — otherwise leftover entries for actor
# "consumer" from an earlier run of this script, or from Task 6's own probe (which
# uses the same participant id), inflate this run's sealed-entry count and make the
# "1 sealed entry" expectation below unreliable. Task 6's probe established this same
# pattern for the same reason.
curl -s -o /dev/null -X DELETE "http://admin:benefits@localhost:5984/benefits-audit"
# EPOCH_SIZE=1 so a single negotiation seals its own epoch immediately, rather than
# needing 50 real negotiations to accumulate before this probe can observe a seal.
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.audit.epochSize=1
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-e2e-asset",
  "properties": { "name": "audit e2e asset" },
  "dataAddress": { "type": "test" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-e2e-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate "$WORK/policy.json")
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

# The negotiation event fires asynchronously relative to the negotiation reaching
# FINALIZED in the management API's own view, so give the audit extension a moment to
# record and seal before querying the endpoint.
sleep 2

echo "--- fetching audit response for consumer ---"
curl -sf "http://localhost:19197/audit/consumer" | tee "$WORK/response.json"
echo

(cd "$ROOT/packages/audit-verify" && npm run build >/dev/null)
node "$ROOT/tools/verify-audit-response.mjs" "http://localhost:19197/audit" consumer
