#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

CASE=$1
WORK=/tmp/benefits-case
rm -rf "$WORK" && mkdir -p "$WORK"

PURPOSE=service-improvement
RECIPIENT=statistics
FIELDS=service.satisfaction
SEED=""
ARTIFACT_K=6
ARTIFACT_EPS=0.5

case "$CASE" in
  quorum-met)                   SEED="s-1 s-2" ;;
  quorum-missing)               SEED="" ;;
  quorum-one-steward-twice)     SEED="s-1 s-1" ;;
  purpose-mismatch)             SEED="s-1 s-2"; PURPOSE=marketing ;;
  recipient-class-mismatch)     SEED="s-1 s-2"; RECIPIENT=provider ;;
  field-outside-grant)          SEED="s-1 s-2"; FIELDS="service.satisfaction,health.condition" ;;
  expired-grant-field-excluded) SEED="s-1 s-2"; FIELDS="health.condition" ;;
  k-below-threshold)            SEED="s-1 s-2"; ARTIFACT_K=4;   ARTIFACT_EPS=0.5 ;;
  budget-exceeded)              SEED="s-1 s-2"; ARTIFACT_K=6;   ARTIFACT_EPS=0.9 ;;
  *) echo "unknown case: $CASE" >&2; exit 2 ;;
esac

node "$ROOT/tools/compile-policy.mjs" "$CASE" > "$WORK/policy.json"

SEED_ARG=()
if [ -n "$SEED" ]; then
  python3 - "$CASE" "$WORK/seed.json" $SEED <<'PY'
import json, sys
case, out = sys.argv[1], sys.argv[2]
stewards = sys.argv[3:]
json.dump([{
    "releaseRequestId": "rr-" + case,
    "datasetHash": "hash-" + case,
    "issuedAt": "2026-08-30T12:00:00Z",
    "signatures": [
        {"stewardId": s, "signature": "sig-" + s + "-" + str(i), "signedAt": "2026-08-30T12:00:00Z"}
        for i, s in enumerate(stewards)
    ]
}], open(out, "w"))
PY
  SEED_ARG=(-Dbenefits.seed.authorizations="$WORK/seed.json")
fi

python3 - "$CASE" "$WORK/artifacts.json" "$ARTIFACT_K" "$ARTIFACT_EPS" <<'PY'
import json, sys
case, out, k, eps = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4])
json.dump([{ "releaseRequestId": "rr-" + case, "payload": "{\"groups\":[]}",
             "computedK": k, "epsilonCharged": eps }], open(out, "w"))
PY
SEED_ARG+=(-Dbenefits.seed.artifacts="$WORK/artifacts.json")

# This script never runs a real transfer, so there is no asset DataAddress to check
# against a release request id here (that check belongs to probe-aggregate-transfer.sh).
# What is present, and derived from $CASE in two separate places, is the id the seeded
# ReleaseArtifact is stored under and the id the compiled policy's benefits:releaseRequest
# constraint carries. KAnonymityFunction and EpsilonBudgetFunction resolve the latter and
# use it to look up the former; nothing at the EDC framework level checks they agree, so
# assert it here.
POLICY_RR=$(python3 -c "
import json
policy = json.load(open('$WORK/policy.json'))['policy']
constraints = {c['leftOperand']: c['rightOperand'] for c in policy['permission'][0]['constraint']}
print(constraints['benefits:releaseRequest'])
")
ARTIFACT_RR=$(python3 -c "import json; print(json.load(open('$WORK/artifacts.json'))[0]['releaseRequestId'])")
if [ "$POLICY_RR" != "$ARTIFACT_RR" ]; then
  echo "release request id mismatch: policy benefits:releaseRequest=$POLICY_RR seeded artifact releaseRequestId=$ARTIFACT_RR" >&2
  exit 1
fi

sed -e "s|^benefits.requester.purpose=.*|benefits.requester.purpose=$PURPOSE|" \
    -e "s|^benefits.requester.recipientClass=.*|benefits.requester.recipientClass=$RECIPIENT|" \
    -e "s|^benefits.requester.fields=.*|benefits.requester.fields=$FIELDS|" \
    "$ROOT/connector/runtime/consumer.properties" > "$WORK/consumer.properties"

trap stop_all EXIT

stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 "${SEED_ARG[@]}"
start_connector consumer "$WORK/consumer.properties" 29193
publish_offer "$WORK/policy.json"
negotiate "$WORK/policy.json"
