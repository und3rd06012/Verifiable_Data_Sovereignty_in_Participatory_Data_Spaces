import { useState } from 'react';
import { Queue } from './Queue';
import { ProvisionParticipant } from './ProvisionParticipant';
import { GATEWAY_URL } from './config';

export type Session = { stewardId: string; token: string };
type Tab = 'queue' | 'provision';

type LoginStage = { stage: 'second-factor' | 'enroll'; username: string; role: string; methods: string[] };

async function post(path: string, body: unknown): Promise<{ ok: boolean; body: Record<string, unknown> }> {
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
  return { ok: response.ok, body: parsed };
}

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>('queue');

  if (!session) {
    return <LoginFlow onLogin={setSession} />;
  }

  const token = session.token;

  function handleLogout() {
    fetch(`${GATEWAY_URL}/auth/logout`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}` },
    }).catch(() => undefined).finally(() => setSession(null));
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '1rem' }}>
        <h1 style={{ margin: 0, fontSize: '1.15rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          BENEFITS - Steward Portal ({session.stewardId})
        </h1>
        <button
          onClick={handleLogout}
          style={{
            marginLeft: 'auto',
            flexShrink: 0,
            background: '#c0392b',
            color: '#ffffff',
            border: 'none',
            borderRadius: '6px',
            padding: '0.45rem 0.9rem',
            fontSize: '0.9rem',
            cursor: 'pointer',
          }}
        >
          Log out
        </button>
      </div>
      <nav>
        <button className={tab === 'queue' ? 'active' : ''} onClick={() => setTab('queue')}>Review queue</button>
        <button className={tab === 'provision' ? 'active' : ''} onClick={() => setTab('provision')}>Provision participant</button>
      </nav>
      {tab === 'queue' && <Queue session={session} />}
      {tab === 'provision' && <ProvisionParticipant session={session} />}
    </div>
  );
}

function LoginFlow({ onLogin }: { onLogin: (session: Session) => void }) {
  const [step, setStep] = useState<'password' | 'twofa' | 'enroll'>('password');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [methods, setMethods] = useState<string[]>([]);
  const [method, setMethod] = useState('totp');
  const [code, setCode] = useState('');
  const [otpauth, setOtpauth] = useState<{ otpauthUri: string; secret: string } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const redBtn: React.CSSProperties = {
    background: '#c0392b',
    color: '#ffffff',
    border: 'none',
    borderRadius: '6px',
    padding: '0.55rem 1rem',
    cursor: 'pointer',
    fontSize: '1rem',
  };

  async function submitPassword(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const { ok, body } = await post('/auth/login', { username, password });
      if (!ok) {
        throw new Error(String(body.error ?? 'login failed'));
      }
      const stage = body as unknown as LoginStage;
      setMethods(stage.methods);
      setMethod(stage.methods[0] ?? 'totp');
      setStep(stage.stage === 'enroll' ? 'enroll' : 'twofa');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'login failed');
    } finally {
      setBusy(false);
    }
  }

  async function sendCode() {
    setBusy(true);
    setError('');
    try {
      const { ok, body } = await post('/auth/request', { username, password, method });
      if (!ok) {
        throw new Error(String(body.error ?? 'failed to send code'));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'failed to send code');
    } finally {
      setBusy(false);
    }
  }

  async function submitTwoFactor(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const { ok, body } = await post('/auth/verify', { username, password, method, code });
      if (!ok) {
        throw new Error(String(body.error ?? 'verification failed'));
      }
      onLogin({ stewardId: username, token: String(body.token) });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'verification failed');
    } finally {
      setBusy(false);
    }
  }

  async function setupTotp() {
    setBusy(true);
    setError('');
    try {
      const { ok, body } = await post('/auth/enroll', { username, password, method: 'totp' });
      if (!ok) {
        throw new Error(String(body.error ?? 'enrollment failed'));
      }
      setOtpauth({ otpauthUri: String(body.otpauthUri ?? ''), secret: String(body.secret ?? '') });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'enrollment failed');
    } finally {
      setBusy(false);
    }
  }

  if (step === 'enroll') {
    return (
      <form onSubmit={(e) => { e.preventDefault(); setMethods(['totp']); setMethod('totp'); setStep('twofa'); }}>
        <h1>BENEFITS - Steward Portal</h1>
        <p>This account has no second factor yet. Set one up before continuing.</p>
        <button type="button" disabled={busy} onClick={setupTotp} style={redBtn}>Set up authenticator app (TOTP)</button>
        {otpauth && (
          <div>
            <p>Scan this with your authenticator app, or enter the secret manually:</p>
            <p><code style={{ wordBreak: 'break-all' }}>{otpauth.otpauthUri}</code></p>
            <p>Secret: <code>{otpauth.secret}</code></p>
          </div>
        )}
        {otpauth && <button type="submit" style={redBtn}>I have added it, continue to code</button>}
        {error && <p>{error}</p>}
      </form>
    );
  }

  if (step === 'twofa') {
    return (
      <form onSubmit={submitTwoFactor}>
        <h1>BENEFITS - Steward Portal</h1>
        <p>Second factor for {username}</p>
        <label>
          Method
          <select value={method} onChange={(e) => setMethod(e.target.value)}>
            {methods.map((m) => <option key={m} value={m}>{m === 'totp' ? 'Authenticator app (TOTP)' : m === 'email' ? 'Email code' : 'SMS code'}</option>)}
          </select>
        </label>
        {method !== 'totp' && <button type="button" disabled={busy} onClick={sendCode} style={redBtn}>Send code</button>}
        <label>
          Code
          <input value={code} onChange={(e) => setCode(e.target.value)} required autoComplete="one-time-code" />
        </label>
        <button type="submit" disabled={busy} style={redBtn}>Verify and continue</button>
        {error && <p>{error}</p>}
      </form>
    );
  }

  return (
    <form onSubmit={submitPassword}>
      <h1>BENEFITS - Steward Portal</h1>
      <label>
        Steward ID
        <input value={username} onChange={(e) => setUsername(e.target.value)} required />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <button type="submit" disabled={busy} style={redBtn}>Continue</button>
      {error && <p>{error}</p>}
    </form>
  );
}
