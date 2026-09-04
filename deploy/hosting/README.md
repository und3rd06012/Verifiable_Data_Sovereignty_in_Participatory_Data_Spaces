# Hosting BENEFITS on a VPS

This stack serves three web apps behind one nginx, with the auth gateway as
the only backend entry point:

- `/`, participant web app (React)
- `/mobile/`, the Flutter mobile app built for web
- `/steward/`, steward portal (React)
- `/api/`, auth gateway (2FA login, then proxies to CouchDB, the steward
  API, and the audit API on the internal Docker network)

CouchDB and the EDC connector are on an internal network only. They are not
published to the host.

## 1. Server prerequisites

- Ubuntu 22.04+ (or similar), ports 80 and 443 open
- Docker Engine + Compose v2
- A domain name with an A record pointing at the server (optional but
  recommended; without one, use the self-signed path below)

## 2. Build the web artifacts

On the server (or your machine, then copy the repo):

```bash
cd /home/und3rd06012/benefits
./deploy/hosting/build-apps.sh
```

This builds the three web apps with `GATEWAY=/api` baked in, and the
connector jar.

## 3. TLS certificates

Option A, real certificates with Let's Encrypt (recommended for a public
demo):

```bash
sudo apt-get install -y certbot
sudo certbot certonly --standalone -d your-domain.example
sudo mkdir -p deploy/hosting/certs
sudo cp /etc/letsencrypt/live/your-domain.example/fullchain.pem deploy/hosting/certs/fullchain.pem
sudo cp /etc/letsencrypt/live/your-domain.example/privkey.pem deploy/hosting/certs/privkey.pem
```

Option B, self-signed for a quick private demo:

```bash
./deploy/hosting/certs/generate-self-signed.sh
```

## 4. Start the stack

```bash
export COUCHDB_PASSWORD='a-long-random-password'
cd deploy/hosting
docker compose -f docker-compose.hosting.yml up -d --build
```

Set `COUCHDB_PASSWORD` to something real. The first `up` builds the gateway
image and pulls couchdb, nginx, and the JRE.

Check it:

```bash
curl -k https://your-domain.example/api/healthz
```

## 5. Provision users with 2FA

The provisioning scripts create the CouchDB user, the isolated participant
database, the consent validator, and (when `GATEWAY_URL` is set) enroll a
TOTP secret so the first login already requires the second factor.

```bash
export GATEWAY_URL=https://your-domain.example/api
./tools/provision-participant.sh p-001
./tools/provision-steward.sh steward-ana
```

The script prints the one-time password and the TOTP secret plus the
`otpauth://` URI. Scan the URI with Google Authenticator or Aegis, or enter
the secret manually.

## 6. The demo

1. Open `https://your-domain.example/`, participant web app.
2. Log in with the participant ID, password, and the 6-digit TOTP code.
3. Use the Demo questionnaire tab to grant consent, queue a contribution,
   sync it, then queue one with the ungranted `demo.income` field and watch
   S1 refuse it.
4. Open `https://your-domain.example/mobile/`, same flow, Flutter web.
5. Open `https://your-domain.example/steward/`, steward portal, log in
   with the steward ID, password, and TOTP. Review the release queue,
   co-sign or veto.

## 7. Email and SMS second factors

The gateway supports all three methods. TOTP works out of the box. Email
and SMS are sent through webhooks so you can plug any provider:

```bash
export EMAIL_WEBHOOK_URL=https://your-email-service.example/send
export SMS_WEBHOOK_URL=https://your-sms-service.example/send
docker compose -f deploy/hosting/docker-compose.hosting.yml up -d
```

The gateway POSTs `{"to": "...", "subject": "BENEFITS login code", "code":
"123456"}` to the email webhook and `{"to": "...", "code": "123456"}` to
the SMS webhook. With no webhook configured, codes are only logged inside
the container (`docker logs deploy-hosting-auth-gateway-1`), which is the
safe behavior for a private demo.

Enroll a user's email or phone:

```bash
curl -k -X POST https://your-domain.example/api/auth/enroll \
  -H 'content-type: application/json' \
  -d '{"username":"p-001","password":"<their password>","method":"email","contact":"participant@example.com"}'
```

## 8. Security notes

- `DEV_MODE=false` in the compose file, so codes are never returned by the
  API and never logged when webhooks are configured.
- Sessions are random 256-bit tokens held in gateway memory, 8 hour TTL.
  Restarting the gateway logs everyone out.
- CouchDB and the connector are not published to the host.
- The participant's CouchDB password is held in gateway memory only while a
  session is active; it is not stored on the client after login.
- For a production pilot, replace the mock EDC identity module, put CouchDB
  data on an encrypted volume, and set `ALLOWED_ORIGIN` to your domain.
