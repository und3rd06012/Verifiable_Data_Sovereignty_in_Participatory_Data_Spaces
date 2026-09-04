# BENEFITS demo runbook

This runbook takes one machine from a fresh checkout to a working demo of the
BENEFITS sovereignty pipeline, in two flavors:

1. **Scripted demo** (terminal, 5 minutes). Real CouchDB and a real EDC
   connector refuse and release data. Fast and reproducible.
2. **Web UI demo** (browser, 10 minutes). A visitor logs in as a participant,
   fills the demo questionnaire, grants per-field consent, and watches the
   database refuse a field they did not consent to. A steward then reviews a
   release request.

No survey software is involved. The "questionnaire" is a fixed five-field
intake form whose only purpose is to give the consent model something to
enforce.

---

## 1. What the demo shows

| Moment | Mechanism | Proof |
|---|---|---|
| Participant queues a record with a field nothing grants | S1, `validate_doc_update` | CouchDB returns `forbidden: no-grant:<field>` |
| The same record without that field syncs | S1 | `pushed 1 record` |
| Release requested without steward quorum | S2, `StewardQuorumFunction` | negotiation `TERMINATED` |
| Release with two signatures from the same steward | S2 | `TERMINATED` (distinct stewards required) |
| Purpose or recipient class outside the grant | S2, ODRL constraint | `TERMINATED` |
| k below threshold or epsilon budget exhausted | S2, privacy functions | `TERMINATED` |
| Everything satisfied | S2 | negotiation `FINALIZED`, data plane moves bytes |
| Participant opens audit trail | audit chain | own entries with inclusion proof |

---

## 2. Prerequisites

- Docker (for CouchDB) or a local CouchDB on `localhost:5984`
- Node.js 20+ and npm
- JDK 17+ for the connector and Java experiments
- Flutter SDK for the mobile web build
- Ports free: `5984` (CouchDB), `8080` (auth gateway), `5173` (participant
  web), `19191-19196` and `29191-29196` (connector pair), `19197`/`19199`
  (audit/steward APIs)

All logins now go through the 2FA auth gateway (`services/auth-gateway`).
Passwords are checked first, then a second factor (TOTP, email code, or SMS
code). The gateway is the only component the web apps talk to.

---

## 3. One-time build

```bash
cd /home/und3rd06012/benefits
npm install
npm run build --workspaces --if-present      # TypeScript packages, incl. the S1 design doc
./gradlew shadowJar                          # connector jar (needed for the S2 scripts)
```

---

## 4. Start the backend

```bash
docker compose -f deploy/docker-compose.yml up -d
curl -sf http://admin:benefits@localhost:5984/_up   # {"status":"ok",...}

npm run build -w @benefits/auth-gateway
PORT=8080 COUCHDB_URL=http://localhost:5984 DEV_MODE=true node services/auth-gateway/dist/index.js &
curl -s http://localhost:8080/healthz
```

CouchDB and the gateway are the only backends the S1 web demo needs. The S2
scripts start their own connector pair on demand. `DEV_MODE=true` prints
email and SMS codes to the gateway console, which is exactly what you want
for a local demo.

---

## 5. Scripted demo (terminal)

### 5.1 S1 refusal at the device boundary

```bash
./tools/demo-s1.sh
```

Expected output: a record carrying both `service.satisfaction` (granted) and
`health.condition` (not granted) is refused by the design doc installed inside
CouchDB, and the same record without the ungranted field is accepted. The
script cleans up after itself.

### 5.2 S2 release gate, nine real contract negotiations

```bash
./tools/violations.sh
```

Starts a provider and consumer connector, runs nine negotiations, and writes a
markdown table of outcomes to `docs/results/c1-consent-cases.md`. Eight are
refused each for a distinct, verified reason; one (`quorum-met`) finalizes.

For a single quick negotiation instead of all nine:

```bash
./tools/negotiate.sh
```

### 5.3 Data plane: real bytes move on release

```bash
./tools/probe-transfer.sh                 # asset transfer end to end
./tools/probe-aggregate-transfer.sh       # aggregate through the privacy pipeline
```

---

## 6. Web UI demo (browser)

### 6.1 Provision a participant and a steward

```bash
export GATEWAY_URL=http://localhost:8080
./tools/provision-participant.sh demo-p1    # prints password + TOTP secret/URI
./tools/provision-steward.sh steward-ana    # prints password + TOTP secret/URI
```

`provision-participant.sh` creates the CouchDB user, an isolated
`participant-demo-p1` database, its `_security` document, installs the
consent validator design doc, and enrolls a TOTP secret through the gateway.
Scan the printed `otpauth://` URI with an authenticator app. Run it once per
demo.

### 6.2 Start the participant web app

```bash
npm run dev -w @benefits/participant-web
```

Open `http://localhost:5173`, log in with `demo-p1`, the printed password,
and the 6-digit code from your authenticator app.

### 6.3 The questionnaire flow (the S1 demo)

1. Open the **Demo questionnaire** tab.
2. Answer a few fields (employment status, housing need, health need, service
   accessed, weekly hours).
3. Tick **Consent to share** for the fields you want to allow.
4. Click **Queue contribution**. The record stays local (PouchDB outbox).
5. Click **Sync now**. CouchDB accepts it: `sync ok: pushed 1 record`.
6. Tick **Include demo.income (never granted)**, click
   **Queue with ungranted income field**, then **Sync now**.
7. CouchDB's `validate_doc_update` refuses the write:
   `sync REFUSED by CouchDB validate_doc_update: no-grant:demo.income`.

That refusal is the S1 gate, running inside the database itself, not in the
app. Even a misbehaving client cannot bypass it.

### 6.4 Steward review (optional, needs the connector)

Start the provider connector with the steward and release-request features
turned on:

```bash
./gradlew shadowJar
java -Dbenefits.releaseRequests.enabled=true \
     -Dbenefits.stewardApi.enabled=true \
     -Dbenefits.privacy.persistence.enabled=true \
     -Dbenefits.steward.persistence.enabled=true \
     -Dedc.fs.config="$PWD/connector/runtime/provider.properties" \
     -jar connector/runtime/build/libs/connector.jar
```

In a second terminal:

```bash
npm run dev -w @benefits/steward-portal
```

Open the steward portal (default Vite URL), log in with the steward ID,
password, and TOTP code, review the pending release request, and co-sign or
veto it. The portal talks to the gateway on `8080` by default, which proxies
to the connector's steward API.

### 6.5 Privacy dashboard and audit trail

Back in the participant app, the **Privacy dashboard** shows what was shared
and with whom. The **Audit trail** tab shows the participant's own entries
with inclusion proofs against the sealed epoch chain.

---

## 7. What each refusal reason means

| Reason | Enforcement point |
|---|---|
| `no-grant:<field>` | S1/S2: no active consent grant covers that field |
| `purpose-mismatch` | S2: declared purpose not in the grant |
| `recipient-class-mismatch` | S2: recipient class not in the grant |
| `participant-identity-mismatch` | S1: writer is not the record's participant |
| `quorum not met` | S2: fewer than q distinct steward signatures |
| `k-below-threshold` | S2: group smaller than the k threshold |
| `budget-exceeded` | S2: remaining epsilon too small for the query |

---

## 8. Shutdown

```bash
docker compose -f deploy/docker-compose.yml down     # keep data: omit -v
./tools/negotiate.sh --stop                          # if you used negotiate.sh
```

The connector scripts write PIDs to `/tmp/provider.pid` and
`/tmp/consumer.pid` and clean them up on exit.
