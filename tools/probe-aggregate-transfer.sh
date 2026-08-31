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
  if port_free 4000; then
    echo "sink failed to bind port 4000 (sink working directory: $dir)" >&2
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

cat > "$WORK/asset.json" <<'JSON'
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-aggregate",
  "properties": { "name": "aggregate" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-e2e" }
}
JSON

# Nothing at the EDC framework level checks that the release request id the constraint
# functions look up (from the seeded ReleaseArtifact) is the same id the DataSource will
# actually serve (from the asset's DataAddress). A misconfigured asset could be negotiated
# against one release request's constraints and serve a different release request's bytes.
# This script only ever uses one release request id, so assert the two sources agree.
ASSET_RR=$(python3 -c "import json; print(json.load(open('$WORK/asset.json'))['dataAddress']['releaseRequestId'])")
ARTIFACT_RR=$(python3 -c "import json; print(json.load(open('$WORK/artifacts.json'))[0]['releaseRequestId'])")
if [ "$ASSET_RR" != "$ARTIFACT_RR" ]; then
  echo "release request id mismatch: asset dataAddress.releaseRequestId=$ASSET_RR seeded artifact releaseRequestId=$ARTIFACT_RR" >&2
  exit 1
fi

stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.seed.artifacts="$WORK/artifacts.json"
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d @"$WORK/asset.json" >/dev/null
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
  TRANSFER_TP_ID="$tp_id"

  case "$tstate" in
    COMPLETED|STARTED) ;;
    *) echo "transfer process $tp_id ended in state '$tstate' (expected COMPLETED or STARTED)" >&2; exit 1 ;;
  esac

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
FIRST_TP_ID="$TRANSFER_TP_ID"
run_transfer "$WORK/second"
SECOND_TP_ID="$TRANSFER_TP_ID"

FIRST="$WORK/first/received.data"
SECOND="$WORK/second/received.data"
[ -f "$FIRST" ] && [ -f "$SECOND" ] || { echo "a transfer delivered nothing" >&2; exit 1; }

# Prove not just that the two delivered files match each other, but that each one
# matches the payload that was actually seeded. Two empty files, or two files that
# both received the same wrong content, would otherwise pass the mutual comparison.
python3 -c "
import json
with open('$WORK/artifacts.json') as f:
    seeded = next(a for a in json.load(f) if a['releaseRequestId'] == 'rr-e2e')
with open('$WORK/expected.data', 'w') as out:
    out.write(seeded['payload'])
"

if [ ! -s "$FIRST" ] || [ ! -s "$SECOND" ]; then
  echo "at least one transfer delivered an empty file:" >&2
  echo "  first:  $(wc -c < "$FIRST" 2>/dev/null || echo 'missing') bytes at $FIRST" >&2
  echo "  second: $(wc -c < "$SECOND" 2>/dev/null || echo 'missing') bytes at $SECOND" >&2
  exit 1
fi

if ! cmp -s "$FIRST" "$WORK/expected.data"; then
  echo "first transfer delivered bytes that do not match the seeded artifact" >&2
  echo "  expected:  $(cat "$WORK/expected.data")" >&2
  echo "  delivered: $(cat "$FIRST")" >&2
  exit 1
fi

if ! cmp -s "$SECOND" "$WORK/expected.data"; then
  echo "second transfer delivered bytes that do not match the seeded artifact" >&2
  echo "  expected:  $(cat "$WORK/expected.data")" >&2
  echo "  delivered: $(cat "$SECOND")" >&2
  exit 1
fi

if cmp -s "$FIRST" "$SECOND"; then
  echo "BYTE-IDENTICAL ACROSS TRANSFERS"
else
  echo "DIFFERENT BYTES ACROSS TRANSFERS: noise was redrawn, which is a defect" >&2
  echo "  first:  $FIRST (transfer $FIRST_TP_ID)" >&2
  echo "  second: $SECOND (transfer $SECOND_TP_ID)" >&2
  echo "  first sha256:  $(sha256sum "$FIRST" | awk '{print $1}')" >&2
  echo "  second sha256: $(sha256sum "$SECOND" | awk '{print $1}')" >&2
  echo "  first content:  $(cat "$FIRST")" >&2
  echo "  second content: $(cat "$SECOND")" >&2
  exit 1
fi
