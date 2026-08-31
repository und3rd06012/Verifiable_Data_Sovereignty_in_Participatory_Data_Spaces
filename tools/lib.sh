# shellcheck shell=bash
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/connector/runtime/build/libs/connector.jar"
PROVIDER_MGMT=http://localhost:19193/management
CONSUMER_MGMT=http://localhost:29193/management
KEY="x-api-key: password"
JSON="content-type: application/json"
READY_BODY='{"@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},"@type":"QuerySpec"}'
PORTS="19191 19192 19193 19194 19195 19196 29191 29192 29193 29194 29195 29196"

port_free() {
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# Shutdown takes tens of seconds on this host, and the suite restarts both connectors for
# every case, so waiting for process exit and for every port to be released is what keeps
# one case from failing on a port still held by the previous one.
stop_all() {
  local pidfile pid p busy
  for pidfile in /tmp/provider.pid /tmp/consumer.pid; do
    [ -f "$pidfile" ] || continue
    kill "$(cat "$pidfile")" 2>/dev/null || true
  done
  for pidfile in /tmp/provider.pid /tmp/consumer.pid; do
    [ -f "$pidfile" ] || continue
    pid=$(cat "$pidfile")
    for _ in $(seq 1 60); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$pidfile"
  done
  for _ in $(seq 1 60); do
    busy=0
    for p in $PORTS; do
      port_free "$p" || busy=1
    done
    [ "$busy" -eq 0 ] && break
    sleep 1
  done
}

start_connector() {
  local name=$1 config=$2 mgmt_port=$3
  shift 3
  java "$@" -Dedc.fs.config="$config" -jar "$JAR" > "/tmp/$name.log" 2>&1 &
  echo $! > "/tmp/$name.pid"
  for _ in $(seq 1 60); do
    if curl -sf -H "$KEY" -H "$JSON" -X POST \
         "http://localhost:$mgmt_port/management/v3/assets/request" -d "$READY_BODY" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name did not become ready" >&2
  tail -40 "/tmp/$name.log" >&2
  return 1
}

publish_offer() {
  local policy_file=$1
  curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "@id": "benefits-aggregate",
    "properties": { "name": "aggregate release", "contenttype": "application/json" },
    "dataAddress": { "type": "test" }
  }' >/dev/null || { echo "asset creation failed" >&2; return 1; }
  curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$policy_file" >/dev/null \
    || { echo "policy definition creation failed for $policy_file" >&2; return 1; }
  curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "@id": "benefits-definition",
    "accessPolicyId": "benefits-policy",
    "contractPolicyId": "benefits-policy",
    "assetsSelector": [{
      "@type": "CriterionDto",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "benefits-aggregate"
    }]
  }' >/dev/null || { echo "contract definition creation failed" >&2; return 1; }
}

# Runs one negotiation and echoes its terminal state. The final negotiation document is
# left in /tmp/negotiation.json so the caller can read the provider's termination reason.
negotiate() {
  local policy_file=$1
  curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/catalog/request" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
    "counterPartyId": "provider",
    "protocol": "dataspace-protocol-http:2025-1"
  }' > /tmp/catalog.json || true

  if ! python3 "$ROOT/tools/contract-request.py" "$policy_file" > /tmp/contract-request.json 2>/tmp/contract-request.err; then
    echo "NO_OFFER"
    return 0
  fi

  local neg neg_id state=""
  neg=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d @/tmp/contract-request.json)
  neg_id=$(echo "$neg" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')

  for _ in $(seq 1 60); do
    curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$neg_id" > /tmp/negotiation.json
    state=$(python3 -c 'import json,sys; print(json.load(open("/tmp/negotiation.json")).get("state",""))')
    case "$state" in FINALIZED|TERMINATED) break ;; esac
    sleep 1
  done
  echo "$state"
}

# Evidence that a case was refused for its own reason rather than for some unrelated failure.
# EDC 0.18.0 never surfaces the text passed to context.reportProblem, not in the DSP error body
# and not in the provider log even at DEBUG, so the discriminating evidence is the quorum
# function's own log line, the policy refusal in the negotiation error, and the single input
# that each case varies away from the finalizing baseline.
verify_cause() {
  python3 "$ROOT/tools/verify-cause.py" "$1"
}
