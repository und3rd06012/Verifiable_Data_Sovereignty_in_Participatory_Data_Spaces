#!/usr/bin/env bash
# Transfers a stored anonymised artifact twice and proves the bytes are identical.
# Different bytes would mean the DataSource computed rather than served, which is the
# averaging-attack vulnerability the design exists to prevent.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-e2e
rm -rf "$WORK" && mkdir -p "$WORK/first" "$WORK/second"

cat > "$WORK/artifacts.json" <<'JSON'
[{ "releaseRequestId": "rr-e2e", "payload": "{\"groups\":[{\"group\":\"app\",\"count\":6.4821}]}",
   "computedK": 6, "epsilonCharged": 0.5 }]
JSON

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

# A trivial sink: the consumer pushes to this, and we read what arrived. python3 -m
# http.server only implements do_GET/do_HEAD, and HttpData-PUSH sends the payload via
# HTTP POST, so a plain SimpleHTTPRequestHandler answers every push with 501 Unsupported
# method. This tiny handler adds POST/PUT support, dechunks the body when the data source
# streams it without a Content-Length header, and writes it to a file under the work
# directory passed on the command line so each of the two transfers can be pointed at its
# own output directory.
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

SINK_PID=""

start_sink() {
  local dir=$1
  python3 "$WORK/sink_server.py" "$dir" >/dev/null 2>&1 &
  SINK_PID=$!
  echo "$SINK_PID" > "$WORK/sink.pid"
  for _ in $(seq 1 30); do
    port_free 4000 || break
    sleep 1
  done
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
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.seed.artifacts="$WORK/artifacts.json"
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-aggregate",
  "properties": { "name": "aggregate" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-e2e" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-e2e-definition",
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

run_transfer() {
  local dir=$1
  start_sink "$dir"

  local tp tp_id tstate
  tp=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/transferprocesses" -d "{
    \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
    \"@type\": \"TransferRequestDto\",
    \"connectorId\": \"provider\",
    \"counterPartyAddress\": \"http://localhost:19194/protocol/2025-1\",
    \"contractId\": \"$AGREEMENT\",
    \"protocol\": \"dataspace-protocol-http:2025-1\",
    \"transferType\": \"HttpData-PUSH\",
    \"dataDestination\": { \"type\": \"HttpData\", \"baseUrl\": \"http://localhost:4000/\" }
  }")
  tp_id=$(echo "$tp" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')

  for _ in $(seq 1 60); do
    tstate=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/transferprocesses/$tp_id" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
    case "$tstate" in COMPLETED|STARTED|TERMINATED) break ;; esac
    sleep 1
  done
  echo "transfer into $dir: $tstate"

  # The control-plane's transfer process reaches STARTED as soon as the flow is
  # dispatched, but the data plane's own push (the actual DataSourceFactory call and
  # HTTP POST to the sink) happens asynchronously afterward and can retry on its own
  # schedule, so give the file time to actually arrive rather than checking once.
  local found=""
  for _ in $(seq 1 30); do
    if [ -f "$dir/received.data" ]; then
      found=1
      break
    fi
    sleep 1
  done
  if [ -n "$found" ]; then
    echo "delivered into $dir"
  else
    echo "nothing arrived in $dir" >&2
  fi

  stop_sink
}

run_transfer "$WORK/first"
run_transfer "$WORK/second"

FIRST="$WORK/first/received.data"
SECOND="$WORK/second/received.data"
[ -f "$FIRST" ] && [ -f "$SECOND" ] || { echo "a transfer delivered nothing" >&2; exit 1; }

if cmp -s "$FIRST" "$SECOND"; then
  echo "BYTE-IDENTICAL ACROSS TRANSFERS"
else
  echo "DIFFERENT BYTES ACROSS TRANSFERS: noise was redrawn, which is a defect" >&2
  exit 1
fi
