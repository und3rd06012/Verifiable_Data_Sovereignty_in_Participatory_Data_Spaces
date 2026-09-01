#!/usr/bin/env bash
# Gate 4: the full device-sim fleet test, run standalone the same way every
# other phase's end-to-end gate is run from tools/.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

ensure_couchdb
cd "$ROOT/packages/device-sim"
npx vitest run test/fleet.integration.test.ts
