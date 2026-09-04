#!/usr/bin/env bash
# Builds every web artifact the hosting compose needs, with the gateway
# URL baked in as /api (same origin, nginx proxies /api/ to auth-gateway).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "== building TypeScript workspaces =="
npm install --no-audit --no-fund
npm run build --workspaces --if-present

echo "== building participant web (GATEWAY=/api) =="
VITE_GATEWAY_URL=/api npm run build -w @benefits/participant-web

echo "== building steward portal (GATEWAY=/api, base=/steward/) =="
(cd apps/steward-portal && VITE_GATEWAY_URL=/api npx vite build --base=/steward/)

echo "== building Flutter web (GATEWAY=/api) =="
(cd apps/participant-mobile && flutter build web --dart-define=GATEWAY_URL=/api)

echo "== building connector jar =="
./gradlew shadowJar

echo "== all web artifacts ready =="
ls -d apps/participant-web/dist apps/steward-portal/dist apps/participant-mobile/build/web connector/runtime/build/libs/connector.jar
