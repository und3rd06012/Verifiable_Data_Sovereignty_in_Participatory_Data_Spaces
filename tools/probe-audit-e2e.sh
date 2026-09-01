#!/usr/bin/env bash
# Gate 4: a real negotiation and transfer through the running connector produce two
# sealed epochs, and the TypeScript verifier confirms both from the HTTP response
# alone.
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

cat > "$WORK/artifacts.json" <<'JSON'
[{ "releaseRequestId": "rr-audit-e2e", "payload": "{\"groups\":[{\"group\":\"app\",\"count\":6.4821}]}",
   "computedK": 6, "epsilonCharged": 0.5 }]
JSON

# A trivial sink for the transfer below (see tools/probe-aggregate-transfer.sh and
# tools/probe-audit-events.sh, whose identical shape this reuses): python3 -m
# http.server only implements do_GET/do_HEAD, and HttpData-PUSH sends the payload via
# HTTP POST.
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

trap 'stop_sink; stop_all' EXIT
stop_all
ensure_couchdb
# Wipe any prior run's audit state first — otherwise leftover entries for actor
# "consumer" from an earlier run of this script, or from Task 6's own probe (which
# uses the same participant id), inflate this run's sealed-entry count and make the
# "2 sealed entries" expectation below unreliable. Task 6's probe established this
# same pattern for the same reason.
curl -s -o /dev/null -X DELETE "http://admin:benefits@localhost:5984/benefits-audit"
# EPOCH_SIZE=1 so each event seals its own epoch immediately, rather than needing 50
# real events to accumulate before this probe can observe a seal. The asset is
# BenefitsAggregate-typed (backed by a seeded ReleaseArtifact) from the start, not a
# plain "test" type, so the same negotiation this script drives can also be
# transferred afterward — a "test"-type asset has no registered DataSourceFactory and
# a transfer against it would fail at the data plane step.
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.audit.epochSize=1 -Dbenefits.seed.artifacts="$WORK/artifacts.json"
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-e2e-asset",
  "properties": { "name": "audit e2e asset" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-audit-e2e" }
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

AGREEMENT=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$(cat /tmp/negotiation-id.txt)" \
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
echo "transfer: $TSTATE"
[ "$TSTATE" = "COMPLETED" ] || { echo "transfer did not reach COMPLETED (state=$TSTATE)" >&2; exit 1; }

# Both the negotiation-finalized and transfer-completed events fire asynchronously
# relative to their respective management-API state reaching a terminal value, so
# give the audit extension a moment to record and seal both epochs before querying.
sleep 2

echo "--- fetching audit response for consumer ---"
curl -sf "http://localhost:19197/audit/consumer" | tee "$WORK/response.json"
echo

(cd "$ROOT/packages/audit-verify" && npm run build >/dev/null)
node "$ROOT/tools/verify-audit-response.mjs" "http://localhost:19197/audit" consumer
