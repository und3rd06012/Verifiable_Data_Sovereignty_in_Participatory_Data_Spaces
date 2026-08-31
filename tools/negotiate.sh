#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/connector/runtime/build/libs/connector.jar"
RES="$ROOT/tools/resources"
PROVIDER_MGMT=http://localhost:19193/management
CONSUMER_MGMT=http://localhost:29193/management
KEY="x-api-key: password"
JSON="content-type: application/json"
READY_BODY='{"@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},"@type":"QuerySpec"}'
PORTS="19191 19192 19193 19194 19195 19196 29191 29192 29193 29194 29195 29196"

port_free() {
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

cleanup() {
  local pidfile pid
  for pidfile in /tmp/provider.pid /tmp/consumer.pid; do
    [ -f "$pidfile" ] || continue
    pid=$(cat "$pidfile")
    kill "$pid" 2>/dev/null || true
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
    local busy=0 p
    for p in $PORTS; do
      port_free "$p" || busy=1
    done
    [ "$busy" -eq 0 ] && break
    sleep 1
  done
}
trap cleanup EXIT

start() {
  local name=$1 config=$2 port=$3
  java -Dedc.fs.config="$ROOT/connector/runtime/$config" -jar "$JAR" > "/tmp/$name.log" 2>&1 &
  echo $! > "/tmp/$name.pid"
  for _ in $(seq 1 60); do
    if curl -sf -H "$KEY" -H "$JSON" -X POST "http://localhost:$((port + 2))/management/v3/assets/request" -d "$READY_BODY" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name did not become ready" >&2
  tail -40 "/tmp/$name.log" >&2
  return 1
}

start provider provider.properties 19191
start consumer consumer.properties 29191

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets"              -d @"$RES/create-asset.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions"   -d @"$RES/create-policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d @"$RES/create-contract-definition.json" >/dev/null

CATALOG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/catalog/request" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
  "counterPartyId": "provider",
  "protocol": "dataspace-protocol-http:2025-1"
}')
echo "$CATALOG" > /tmp/catalog.json

OFFER_ID=$(python3 -c '
import json, sys
c = json.load(open("/tmp/catalog.json"))
def walk(n):
    if isinstance(n, dict):
        if n.get("@type") in ("odrl:Offer", "Offer") and "@id" in n:
            print(n["@id"]); sys.exit(0)
        for v in n.values(): walk(v)
    elif isinstance(n, list):
        for v in n: walk(v)
walk(c)
sys.exit(1)
')

sed "s|OFFER_ID|$OFFER_ID|" "$RES/contract-request.json" > /tmp/contract-request.json
NEG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d @/tmp/contract-request.json)
NEG_ID=$(echo "$NEG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')

STATE=""
for _ in $(seq 1 60); do
  STATE=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$NEG_ID" \
          | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
  case "$STATE" in FINALIZED|TERMINATED) break ;; esac
  sleep 1
done

echo "negotiation state: $STATE"
echo "$STATE" > /tmp/negotiation-state.txt
