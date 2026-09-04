import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { randomBytes } from 'node:crypto';
import { generateOtp, generateTotpSecret, otpauthUri, verifyTotpCode } from './totp.js';

// ------------------------------------------------------------------ config

const PORT = Number(process.env.PORT ?? 8080);
const COUCHDB_URL = (process.env.COUCHDB_URL ?? 'http://localhost:5984').replace(/\/$/, '');
const ADMIN_USER = process.env.COUCHDB_ADMIN_USER ?? 'admin';
const ADMIN_PASSWORD = process.env.COUCHDB_ADMIN_PASSWORD ?? 'benefits';
const STEWARD_API_URL = (process.env.STEWARD_API_URL ?? 'http://localhost:19199/steward').replace(/\/$/, '');
const AUDIT_API_URL = (process.env.AUDIT_API_URL ?? 'http://localhost:19197/audit').replace(/\/$/, '');
const TOTP_ISSUER = process.env.TOTP_ISSUER ?? 'BENEFITS';
const SESSION_TTL_MS = Number(process.env.SESSION_TTL_HOURS ?? 8) * 3600_000;
const DEV_MODE = process.env.DEV_MODE !== 'false';
const EMAIL_WEBHOOK_URL = process.env.EMAIL_WEBHOOK_URL ?? '';
const SMS_WEBHOOK_URL = process.env.SMS_WEBHOOK_URL ?? '';
const ALLOWED_ORIGIN = process.env.ALLOWED_ORIGIN ?? '*';

type Role = 'participant' | 'steward' | 'unknown';

interface Session {
  username: string;
  password: string;
  role: Role;
  expiresAt: number;
}

interface UserDoc {
  _id: string;
  _rev?: string;
  name?: string;
  roles?: string[];
  benefitsTotpSecret?: string;
  benefitsEmail?: string;
  benefitsSms?: string;
  benefitsEmailOtp?: string;
  benefitsEmailOtpExpires?: number;
  benefitsSmsOtp?: string;
  benefitsSmsOtpExpires?: number;
  [key: string]: unknown;
}

const sessions = new Map<string, Session>();

// ------------------------------------------------------------------ helpers

function json(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

function basic(user: string, pass: string): string {
  return 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64');
}

function bearerToken(req: IncomingMessage): string | null {
  const header = req.headers.authorization ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(header);
  return match ? match[1] : null;
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > 50 * 1024 * 1024) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function parseBody<T>(raw: string): T {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return {} as T;
  }
}

function setCors(res: ServerResponse): void {
  res.setHeader('access-control-allow-origin', ALLOWED_ORIGIN);
  res.setHeader('access-control-allow-methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('access-control-allow-headers', 'content-type,authorization');
  if (ALLOWED_ORIGIN !== '*') {
    res.setHeader('vary', 'origin');
  }
}

// ------------------------------------------------------------------ couchdb

async function verifyCouchPassword(username: string, password: string): Promise<{ ok: boolean; roles: string[] }> {
  const response = await fetch(`${COUCHDB_URL}/_session`, {
    headers: { authorization: basic(username, password) },
  });
  if (!response.ok) {
    return { ok: false, roles: [] };
  }
  const body = (await response.json()) as { userCtx?: { roles?: string[] } };
  return { ok: true, roles: body.userCtx?.roles ?? [] };
}

async function getUserDoc(username: string): Promise<UserDoc | null> {
  const url = `${COUCHDB_URL}/_users/org.couchdb.user:${encodeURIComponent(username)}`;
  const response = await fetch(url, { headers: { authorization: basic(ADMIN_USER, ADMIN_PASSWORD) } });
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    return null;
  }
  return (await response.json()) as UserDoc;
}

async function putUserDoc(username: string, doc: UserDoc): Promise<void> {
  const url = `${COUCHDB_URL}/_users/org.couchdb.user:${encodeURIComponent(username)}`;
  await fetch(url, {
    method: 'PUT',
    headers: {
      authorization: basic(ADMIN_USER, ADMIN_PASSWORD),
      'content-type': 'application/json',
    },
    body: JSON.stringify(doc),
  });
}

function roleFrom(roles: string[]): Role {
  if (roles.includes('steward')) return 'steward';
  if (roles.includes('participant')) return 'participant';
  return 'unknown';
}

async function sendOtp(username: string, method: 'email' | 'sms', address: string, code: string): Promise<void> {
  if (method === 'email' && EMAIL_WEBHOOK_URL) {
    await fetch(EMAIL_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ to: address, subject: 'BENEFITS login code', code }),
    });
  }
  if (method === 'sms' && SMS_WEBHOOK_URL) {
    await fetch(SMS_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ to: address, code }),
    });
  }
  if (DEV_MODE) {
    console.log(`[auth-gateway] ${method.toUpperCase()} code for ${username}: ${code}`);
  }
}

function availableMethods(doc: UserDoc | null): string[] {
  if (!doc) return [];
  const methods: string[] = [];
  if (doc.benefitsTotpSecret) methods.push('totp');
  if (doc.benefitsEmail) methods.push('email');
  if (doc.benefitsSms) methods.push('sms');
  return methods;
}

// ------------------------------------------------------------------ auth routes

async function handleLogin(res: ServerResponse, body: { username?: string; password?: string }): Promise<void> {
  const username = String(body.username ?? '');
  const password = String(body.password ?? '');
  if (!username || !password) {
    json(res, 400, { error: 'username and password are required' });
    return;
  }
  const check = await verifyCouchPassword(username, password);
  if (!check.ok) {
    json(res, 401, { error: 'invalid credentials' });
    return;
  }
  const doc = await getUserDoc(username);
  const methods = availableMethods(doc);
  if (methods.length === 0) {
    json(res, 200, { stage: 'enroll', username, role: roleFrom(check.roles), methods });
    return;
  }
  json(res, 200, { stage: 'second-factor', username, role: roleFrom(check.roles), methods });
}

async function handleRequest(res: ServerResponse, body: { username?: string; password?: string; method?: string }): Promise<void> {
  const username = String(body.username ?? '');
  const password = String(body.password ?? '');
  const method = String(body.method ?? '');
  if (!username || !password || (method !== 'email' && method !== 'sms')) {
    json(res, 400, { error: 'username, password, and method (email or sms) are required' });
    return;
  }
  const check = await verifyCouchPassword(username, password);
  if (!check.ok) {
    json(res, 401, { error: 'invalid credentials' });
    return;
  }
  const doc = await getUserDoc(username);
  const address = method === 'email' ? doc?.benefitsEmail : doc?.benefitsSms;
  if (!address) {
    json(res, 400, { error: `${method} is not enrolled for this user` });
    return;
  }
  const code = generateOtp(6);
  const expires = Date.now() + 10 * 60_000;
  if (method === 'email' && doc) {
    doc.benefitsEmailOtp = code;
    doc.benefitsEmailOtpExpires = expires;
    await putUserDoc(username, doc);
  } else if (method === 'sms' && doc) {
    doc.benefitsSmsOtp = code;
    doc.benefitsSmsOtpExpires = expires;
    await putUserDoc(username, doc);
  }
  await sendOtp(username, method, address, code);
  json(res, 200, { sent: true, hint: 'check your device', devCode: DEV_MODE ? code : undefined });
}

async function handleVerify(res: ServerResponse, body: { username?: string; password?: string; method?: string; code?: string }): Promise<void> {
  const username = String(body.username ?? '');
  const password = String(body.password ?? '');
  const method = String(body.method ?? '');
  const code = String(body.code ?? '').trim();
  if (!username || !password || !method || !code) {
    json(res, 400, { error: 'username, password, method, and code are required' });
    return;
  }
  const check = await verifyCouchPassword(username, password);
  if (!check.ok) {
    json(res, 401, { error: 'invalid credentials' });
    return;
  }
  const doc = await getUserDoc(username);
  let ok = false;
  if (method === 'totp' && doc?.benefitsTotpSecret) {
    ok = verifyTotpCode(doc.benefitsTotpSecret, code);
  } else if (method === 'email' && doc) {
    ok = doc.benefitsEmailOtp === code && (doc.benefitsEmailOtpExpires ?? 0) > Date.now();
    if (ok) {
      doc.benefitsEmailOtp = undefined;
      doc.benefitsEmailOtpExpires = undefined;
      await putUserDoc(username, doc);
    }
  } else if (method === 'sms' && doc) {
    ok = doc.benefitsSmsOtp === code && (doc.benefitsSmsOtpExpires ?? 0) > Date.now();
    if (ok) {
      doc.benefitsSmsOtp = undefined;
      doc.benefitsSmsOtpExpires = undefined;
      await putUserDoc(username, doc);
    }
  }
  if (!ok) {
    json(res, 401, { error: 'invalid or expired code' });
    return;
  }
  const token = randomBytes(32).toString('base64url');
  sessions.set(token, {
    username,
    password,
    role: roleFrom(check.roles),
    expiresAt: Date.now() + SESSION_TTL_MS,
  });
  json(res, 200, { token, username, role: roleFrom(check.roles), expiresAt: new Date(Date.now() + SESSION_TTL_MS).toISOString() });
}

async function handleEnroll(res: ServerResponse, body: { username?: string; password?: string; method?: string; contact?: string }): Promise<void> {
  const username = String(body.username ?? '');
  const password = String(body.password ?? '');
  const method = String(body.method ?? '');
  const contact = String(body.contact ?? '');
  if (!username || !password || !method) {
    json(res, 400, { error: 'username, password, and method are required' });
    return;
  }
  const check = await verifyCouchPassword(username, password);
  if (!check.ok) {
    json(res, 401, { error: 'invalid credentials' });
    return;
  }
  const doc = (await getUserDoc(username)) ?? { _id: `org.couchdb.user:${username}`, name: username, roles: [], type: 'user' };
  if (method === 'totp') {
    if (!doc.benefitsTotpSecret) {
      doc.benefitsTotpSecret = generateTotpSecret();
      await putUserDoc(username, doc);
    }
    json(res, 200, {
      method: 'totp',
      enrolled: true,
      otpauthUri: otpauthUri(TOTP_ISSUER, username, doc.benefitsTotpSecret),
      secret: doc.benefitsTotpSecret,
    });
    return;
  }
  if (method === 'email') {
    if (!contact) {
      json(res, 400, { error: 'contact email is required for email enrollment' });
      return;
    }
    doc.benefitsEmail = contact;
    await putUserDoc(username, doc);
    json(res, 200, { method: 'email', enrolled: true });
    return;
  }
  if (method === 'sms') {
    if (!contact) {
      json(res, 400, { error: 'contact phone is required for sms enrollment' });
      return;
    }
    doc.benefitsSms = contact;
    await putUserDoc(username, doc);
    json(res, 200, { method: 'sms', enrolled: true });
    return;
  }
  json(res, 400, { error: 'unknown method' });
}

// ------------------------------------------------------------------ proxy

async function proxy(
  req: IncomingMessage,
  res: ServerResponse,
  targetBase: string,
  session: Session,
  path: string,
): Promise<void> {
  const body = req.method === 'GET' || req.method === 'HEAD' ? undefined : await readBody(req);
  const headers: Record<string, string> = {
    authorization: basic(session.username, session.password),
  };
  const contentType = req.headers['content-type'];
  if (contentType) headers['content-type'] = String(contentType);
  if (req.headers.accept) headers['accept'] = String(req.headers.accept);

  const upstream = await fetch(`${targetBase}${path}`, {
    method: req.method,
    headers,
    body,
  });

  const responseBody = Buffer.from(await upstream.arrayBuffer());
  res.writeHead(upstream.status, {
    'content-type': upstream.headers.get('content-type') ?? 'application/json',
    ...(upstream.headers.get('etag') ? { etag: upstream.headers.get('etag') as string } : {}),
  });
  res.end(responseBody);
}

function sessionFrom(req: IncomingMessage): Session | null {
  const token = bearerToken(req);
  if (!token) return null;
  const session = sessions.get(token);
  if (!session) return null;
  if (session.expiresAt < Date.now()) {
    sessions.delete(token);
    return null;
  }
  return session;
}

// ------------------------------------------------------------------ server

const server = createServer(async (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const path = url.pathname;

  try {
    if (path === '/healthz') {
      json(res, 200, { ok: true });
      return;
    }

    if (path === '/auth/login' && req.method === 'POST') {
      await handleLogin(res, parseBody(await readBody(req)));
      return;
    }
    if (path === '/auth/request' && req.method === 'POST') {
      await handleRequest(res, parseBody(await readBody(req)));
      return;
    }
    if (path === '/auth/verify' && req.method === 'POST') {
      await handleVerify(res, parseBody(await readBody(req)));
      return;
    }
    if (path === '/auth/enroll' && req.method === 'POST') {
      await handleEnroll(res, parseBody(await readBody(req)));
      return;
    }
    if (path === '/auth/logout' && req.method === 'POST') {
      const token = bearerToken(req);
      if (token) {
        sessions.delete(token);
      }
      json(res, 200, { ok: true });
      return;
    }
    if (path === '/auth/me' && req.method === 'GET') {
      const session = sessionFrom(req);
      if (!session) {
        json(res, 401, { error: 'unauthorized' });
        return;
      }
      json(res, 200, { username: session.username, role: session.role, expiresAt: new Date(session.expiresAt).toISOString() });
      return;
    }

    const session = sessionFrom(req);
    if (!session) {
      json(res, 401, { error: 'unauthorized' });
      return;
    }

    if (path.startsWith('/couch')) {
      await proxy(req, res, COUCHDB_URL, session, path.slice('/couch'.length) || '/');
      return;
    }
    if (path.startsWith('/steward')) {
      if (session.role !== 'steward') {
        json(res, 403, { error: 'steward role required' });
        return;
      }
      await proxy(req, res, STEWARD_API_URL, session, path.slice('/steward'.length) || '/');
      return;
    }
    if (path.startsWith('/audit')) {
      if (session.role !== 'participant') {
        json(res, 403, { error: 'participant role required' });
        return;
      }
      await proxy(req, res, AUDIT_API_URL, session, path.slice('/audit'.length) || '/');
      return;
    }

    json(res, 404, { error: 'not found' });
  } catch (err) {
    console.error('[auth-gateway]', err);
    json(res, 502, { error: 'upstream error' });
  }
});

server.listen(PORT, () => {
  console.log(`[auth-gateway] listening on :${PORT}`);
  console.log(`[auth-gateway] couchdb ${COUCHDB_URL} | steward ${STEWARD_API_URL} | audit ${AUDIT_API_URL}`);
  console.log(`[auth-gateway] dev mode: ${DEV_MODE}`);
});
