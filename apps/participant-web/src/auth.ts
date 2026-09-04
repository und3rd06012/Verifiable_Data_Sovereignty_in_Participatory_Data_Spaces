import { GATEWAY_URL } from './config';

export type LoginStage = {
  stage: 'second-factor' | 'enroll';
  username: string;
  role: 'participant' | 'steward' | 'unknown';
  methods: string[];
};

export type VerifiedSession = {
  token: string;
  username: string;
  role: string;
};

async function post(path: string, body: unknown): Promise<{ ok: boolean; status: number; body: Record<string, unknown> }> {
  const response = await fetch(`${GATEWAY_URL}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  let parsed: Record<string, unknown> = {};
  try {
    parsed = await response.json() as Record<string, unknown>;
  } catch {
    // upstream may not return JSON
  }
  return { ok: response.ok, status: response.status, body: parsed };
}

export async function loginStep1(username: string, password: string): Promise<LoginStage> {
  const { ok, body } = await post('/auth/login', { username, password });
  if (!ok) {
    throw new Error(String(body.error ?? 'login failed'));
  }
  return body as unknown as LoginStage;
}

export async function requestOtp(username: string, password: string, method: 'email' | 'sms'): Promise<void> {
  const { ok, body } = await post('/auth/request', { username, password, method });
  if (!ok) {
    throw new Error(String(body.error ?? 'failed to send code'));
  }
}

export async function verifySecondFactor(
  username: string,
  password: string,
  method: string,
  code: string,
): Promise<VerifiedSession> {
  const { ok, body } = await post('/auth/verify', { username, password, method, code });
  if (!ok) {
    throw new Error(String(body.error ?? 'verification failed'));
  }
  return body as unknown as VerifiedSession;
}

export async function enrollTotp(username: string, password: string): Promise<{ otpauthUri: string; secret: string }> {
  const { ok, body } = await post('/auth/enroll', { username, password, method: 'totp' });
  if (!ok) {
    throw new Error(String(body.error ?? 'enrollment failed'));
  }
  return { otpauthUri: String(body.otpauthUri ?? ''), secret: String(body.secret ?? '') };
}

export function bearerFetch(token: string) {
  return (input: string | URL | Request, opts?: RequestInit): Promise<Response> => {
    const headers = new Headers(opts?.headers);
    headers.set('authorization', `Bearer ${token}`);
    return fetch(input, { ...opts, headers });
  };
}

export async function logout(token: string): Promise<void> {
  try {
    await fetch(`${GATEWAY_URL}/auth/logout`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}` },
    });
  } catch {
    // session is invalidated locally regardless of the network result
  }
}
