#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-probe-transfer
rm -rf "$WORK" && mkdir -p "$WORK"

# A trivial sink: the consumer pushes to this, and we read what arrived.
# python3 -m http.server only implements do_GET/do_HEAD, and HttpData-PUSH sends the
# payload via HTTP POST, so a plain SimpleHTTPRequestHandler answers every push with
# 501 Unsupported method. This tiny handler adds POST/PUT support and writes the body
# to a file so the marker can actually be observed.
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
        # The data source streams from an InputStream of unknown length, so the client
        # sends the body chunked rather than with a Content-Length header. http.server
        # does not dechunk automatically, so do it here.
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
python3 "$WORK/sink_server.py" "$WORK" >/dev/null 2>&1 &
echo $! > "$WORK/sink.pid"
trap 'kill "$(cat "$WORK/sink.pid")" 2>/dev/null || true; stop_all' EXIT

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

stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

# contract-request.py (used by negotiate below) targets the asset id "benefits-aggregate",
# the same convention tools/run-case.sh relies on, so the probe asset keeps that id even
# though its dataAddress type is the probe type rather than "test".
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-aggregate",
  "properties": { "name": "probe" },
  "dataAddress": { "type": "BenefitsProbe" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-probe-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate "$WORK/policy.json")
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

AGREEMENT=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$(cat /tmp/negotiation-id.txt)" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["contractAgreementId"])')
echo "agreement: $AGREEMENT"

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
  case "$TSTATE" in COMPLETED|STARTED|TERMINATED) break ;; esac
  sleep 1
done
echo "transfer state: $TSTATE"

# The control-plane's transfer process reaches STARTED as soon as the flow is dispatched,
# but the data plane's own push (the actual DataSourceFactory call and HTTP POST to the
# sink) happens asynchronously afterward and can retry on its own schedule, so give the
# marker time to actually arrive rather than checking once.
FOUND=""
for _ in $(seq 1 30); do
  if grep -rl BENEFITS_TRANSFER_OK "$WORK" 2>/dev/null; then
    FOUND=1
    break
  fi
  sleep 1
done
if [ -n "$FOUND" ]; then
  echo "MARKER FOUND"
else
  echo "MARKER NOT FOUND" >&2
  exit 1
fi
