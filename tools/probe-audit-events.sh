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

# Seeded up front (harmless to the FINALIZED case below, which never reads it) so the
# same provider instance can also serve the two forced-transfer cases further down
# without a restart: TransferProcessCompleted and TransferProcessTerminated both need a
# BenefitsAggregate asset backed by a real seeded ReleaseArtifact.
cat > "$WORK/artifacts.json" <<'JSON'
[
  { "releaseRequestId": "rr-audit-probe-ok", "payload": "{\"groups\":[{\"group\":\"app\",\"count\":6.4821}]}",
    "computedK": 6, "epsilonCharged": 0.5 },
  { "releaseRequestId": "rr-audit-probe-fail", "payload": "{\"groups\":[{\"group\":\"app\",\"count\":6.4821}]}",
    "computedK": 6, "epsilonCharged": 0.5 }
]
JSON

# Task 6 replaced ProbeEventSubscriber/ProbeAuditResource (the gate 0 probe classes
# this script originally asserted against) with the real BenefitsAuditEventListener
# and CouchDbAuditStore. There is no more "/audit/probe" text response or
# "AUDIT_PROBE" log line to check, so this script now confirms the same two
# underlying facts Task 1 established (EventRouter delivers all four event classes,
# and the "audit" web context is reachable) by querying the real CouchDB-backed
# audit trail the real listener now writes, plus a plain HTTP reachability check
# against the "audit" context.
AUDIT_DB_URL="http://admin:benefits@localhost:5984/benefits-audit"

# Waits for BenefitsAuditEventListener to have recorded at least one auditEntry of
# the given eventType. Recording happens asynchronously relative to the management
# API's own view of negotiation/transfer state, so this polls rather than checking
# once.
wait_for_audit_entry() {
  local event_type=$1
  for _ in $(seq 1 30); do
    if curl -sf -H "$JSON" -X POST "$AUDIT_DB_URL/_find" \
         -d "{\"selector\":{\"type\":\"auditEntry\",\"eventType\":\"$event_type\"},\"limit\":1}" \
         | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("docs") else 1)' 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

trap 'stop_all' EXIT
stop_all
ensure_couchdb || exit 1
# Clean slate so this run's own audit trail is the only evidence wait_for_audit_entry
# can find; must happen before the provider boots (CouchDbAuditStore creates the
# database at construction time, and deleting it out from under a live store breaks
# every subsequent write).
curl -s -o /dev/null -X DELETE "$AUDIT_DB_URL"
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.seed.artifacts="$WORK/artifacts.json"
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

echo "--- probing audit web context ---"
AUDIT_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:19197/audit/")
echo "audit web context responded with HTTP $AUDIT_HTTP_CODE"
[ "$AUDIT_HTTP_CODE" != "000" ] \
  || { echo "audit web context unreachable (PortMapping/WebService registration regression)" >&2; exit 1; }

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

echo "--- confirming BenefitsAuditEventListener recorded the finalized negotiation ---"
wait_for_audit_entry negotiation-finalized \
  || { echo "no negotiation-finalized audit entry recorded" >&2; exit 1; }
echo "gate 0 probe passed"

# -----------------------------------------------------------------------------------
# Extended gate 0: the design spec (section 2) names four event classes the real audit
# extension (Task 6) must handle. The FINALIZED negotiation above only ever exercises
# one of them. Force the other three against this same live provider so Task 6's
# transfer/termination listeners rest on events actually observed firing in this
# runtime, not only on the spec's claims about their shape.
#
# "audit-probe-definition" above was published with assetsSelector: [] (matches every
# asset), so every asset published from here on is automatically covered by it under
# the same "benefits-policy" policy, no extra contract definitions are needed. Each
# ContractRequest below is built directly (bypassing tools/contract-request.py's
# catalog-scraping, which is unsuited to a catalog holding more than one asset) so each
# negotiation targets exactly the asset intended.
# -----------------------------------------------------------------------------------

# The catalog assigns each dataset's offer a composite id (base64 contract-definition id,
# base64 asset id, base64 random uuid, colon-joined) generated fresh per catalog request -
# it is not the raw policy definition id ("benefits-policy" would 404 at negotiation time
# once more than one dataset exists in the catalog, discovered while building this probe).
# So each request below fetches the catalog and reads the real offer id for its own target
# asset off the matching dataset entry, rather than reusing tools/contract-request.py's
# first-offer-in-the-document heuristic, which is unsuited to a catalog holding more than
# one asset.
offer_id_for() {
  local target=$1
  curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/catalog/request" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
    "counterPartyId": "provider",
    "protocol": "dataspace-protocol-http:2025-1"
  }' | python3 -c "
import json, sys
target = '$target'
catalog = json.load(sys.stdin)
datasets = catalog.get('dataset', [])
if isinstance(datasets, dict):
    datasets = [datasets]
for ds in datasets:
    if ds.get('@id') == target:
        policies = ds.get('hasPolicy', [])
        if isinstance(policies, dict):
            policies = [policies]
        print(policies[0]['@id'])
        sys.exit(0)
sys.exit(1)
"
}

request_for() {
  local target=$1
  local offer_id
  offer_id=$(offer_id_for "$target")
  cat <<JSON
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractRequest",
  "counterPartyId": "provider",
  "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
  "protocol": "dataspace-protocol-http:2025-1",
  "policy": {
    "@context": [ "http://www.w3.org/ns/odrl.jsonld" ],
    "@id": "$offer_id",
    "@type": "Offer",
    "permission": [ { "action": "use", "target": "$target" } ],
    "prohibition": [], "obligation": [],
    "assigner": "provider",
    "target": "$target"
  }
}
JSON
}

negotiation_state() {
  curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))'
}

echo
echo "--- forcing ContractNegotiationTerminated ---"
NEG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d "$(request_for audit-probe-asset)")
NEG_ID=$(echo "$NEG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')
for _ in $(seq 1 60); do
  ST=$(negotiation_state "$NEG_ID")
  case "$ST" in ""|INITIAL|REQUESTING) sleep 0.3 ;; *) break ;; esac
done
curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations/$NEG_ID/terminate" -d "{
  \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
  \"@type\": \"https://w3id.org/edc/v0.0.1/ns/TerminateNegotiation\",
  \"@id\": \"$NEG_ID\",
  \"reason\": \"audit probe forced termination\"
}" >/dev/null
for _ in $(seq 1 30); do
  ST=$(negotiation_state "$NEG_ID")
  case "$ST" in FINALIZED|TERMINATED) break ;; esac
  sleep 0.5
done
echo "negotiation termination: $ST"
[ "$ST" = "TERMINATED" ] || { echo "forced negotiation termination did not reach TERMINATED (state=$ST)" >&2; exit 1; }

# A trivial sink for the two transfer cases below: python3 -m http.server only implements
# do_GET/do_HEAD, and HttpData-PUSH sends the payload via HTTP POST, so a plain
# SimpleHTTPRequestHandler answers every push with 501 Unsupported method. This tiny
# handler adds POST/PUT support and dechunks the body when the data source streams it
# without a Content-Length header (see tools/probe-aggregate-transfer.sh, whose shape
# this reuses).
cat > "$WORK/sink_server.py" <<'PY'
import http.server
import os
import sys

work = sys.argv[1]


class Handler(http.server.BaseHTTPRequestHandler):
    def _read_chunked(self):
        body = b''
        while True:
            size_line = self.rfile.readline().strip()
            size = int(size_line.split(b';')[0], 16)
            if size == 0:
                self.rfile.readline()
                break
            body += self.rfile.read(size)
            self.rfile.readline()
        return body

    def _receive(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            body = self._read_chunked()
        else:
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
        with open(os.path.join(work, 'received.data'), 'wb') as f:
            f.write(body)
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        self._receive()

    def do_PUT(self):
        self._receive()

    def log_message(self, fmt, *args):
        pass


http.server.HTTPServer(('127.0.0.1', 4000), Handler).serve_forever()
PY

SINK_PID=""
start_sink() {
  python3 "$WORK/sink_server.py" "$WORK" >/dev/null 2>&1 &
  SINK_PID=$!
  for _ in $(seq 1 30); do
    port_free 4000 || break
    sleep 1
  done
  if port_free 4000; then
    echo "sink failed to bind port 4000" >&2
    exit 1
  fi
}
stop_sink() {
  [ -n "$SINK_PID" ] && kill "$SINK_PID" 2>/dev/null || true
  for _ in $(seq 1 30); do
    port_free 4000 && break
    sleep 1
  done
  SINK_PID=""
}

echo
echo "--- forcing TransferProcessCompleted ---"
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-probe-transfer-asset",
  "properties": { "name": "audit probe transfer asset" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-audit-probe-ok" }
}' >/dev/null

NEG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d "$(request_for audit-probe-transfer-asset)")
NEG_ID=$(echo "$NEG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')
for _ in $(seq 1 60); do
  ST=$(negotiation_state "$NEG_ID")
  case "$ST" in FINALIZED|TERMINATED) break ;; esac
  sleep 1
done
[ "$ST" = "FINALIZED" ] || { echo "transfer-completed setup negotiation did not finalize (state=$ST)" >&2; exit 1; }
AGREEMENT=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$NEG_ID" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["contractAgreementId"])')

start_sink
TP=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/transferprocesses" -d "{
  \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
  \"@type\": \"TransferRequestDto\",
  \"connectorId\": \"provider\",
  \"counterPartyAddress\": \"http://localhost:19194/protocol/2025-1\",
  \"contractId\": \"$AGREEMENT\",
  \"protocol\": \"dataspace-protocol-http:2025-1\",
  \"transferType\": \"HttpData-PUSH\",
  \"dataDestination\": { \"type\": \"HttpData\", \"baseUrl\": \"http://localhost:4000/\" }
}")
TP_ID=$(echo "$TP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')
for _ in $(seq 1 60); do
  TSTATE=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/transferprocesses/$TP_ID" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
  case "$TSTATE" in COMPLETED|TERMINATED) break ;; esac
  sleep 1
done
stop_sink
echo "transfer (completed case): $TSTATE"
[ "$TSTATE" = "COMPLETED" ] || { echo "forced transfer completion did not reach COMPLETED (state=$TSTATE)" >&2; exit 1; }

echo
echo "--- forcing TransferProcessTerminated ---"
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-probe-transfer-fail-asset",
  "properties": { "name": "audit probe transfer fail asset" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-audit-probe-fail" }
}' >/dev/null

NEG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d "$(request_for audit-probe-transfer-fail-asset)")
NEG_ID=$(echo "$NEG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')
for _ in $(seq 1 60); do
  ST=$(negotiation_state "$NEG_ID")
  case "$ST" in FINALIZED|TERMINATED) break ;; esac
  sleep 1
done
[ "$ST" = "FINALIZED" ] || { echo "transfer-terminated setup negotiation did not finalize (state=$ST)" >&2; exit 1; }
AGREEMENT=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$NEG_ID" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["contractAgreementId"])')

# Nothing listens on 4009: the data plane's push retries against an unreachable sink
# and eventually gives up, terminating the transfer process rather than completing it.
TP=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/transferprocesses" -d "{
  \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
  \"@type\": \"TransferRequestDto\",
  \"connectorId\": \"provider\",
  \"counterPartyAddress\": \"http://localhost:19194/protocol/2025-1\",
  \"contractId\": \"$AGREEMENT\",
  \"protocol\": \"dataspace-protocol-http:2025-1\",
  \"transferType\": \"HttpData-PUSH\",
  \"dataDestination\": { \"type\": \"HttpData\", \"baseUrl\": \"http://localhost:4009/\" }
}")
TP_ID=$(echo "$TP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')
for _ in $(seq 1 60); do
  TSTATE=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/transferprocesses/$TP_ID" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
  case "$TSTATE" in COMPLETED|TERMINATED) break ;; esac
  sleep 1
done
echo "transfer (terminated case): $TSTATE"
[ "$TSTATE" = "TERMINATED" ] || { echo "forced transfer termination did not reach TERMINATED (state=$TSTATE)" >&2; exit 1; }

echo
echo "--- confirming BenefitsAuditEventListener recorded all four event types ---"
for event_type in negotiation-finalized negotiation-terminated transfer-completed transfer-terminated; do
  wait_for_audit_entry "$event_type" \
    || { echo "no $event_type audit entry recorded" >&2; exit 1; }
done
echo "gate 0 probe passed (extended: all four event types recorded by the real listener)"
