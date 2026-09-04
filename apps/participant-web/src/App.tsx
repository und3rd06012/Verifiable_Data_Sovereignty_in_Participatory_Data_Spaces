import { useState } from 'react';
import { ParticipantClient } from '@benefits/consent-web-client';
import { ConsentCards } from './ConsentCards';
import { PrivacyDashboard } from './PrivacyDashboard';
import { AuditViewer } from './AuditViewer';
import { DemoQuestionnaire } from './DemoQuestionnaire';
import { enrollTotp, loginStep1, logout, requestOtp, verifySecondFactor } from './auth';

export type Session = { participantRef: string; token: string };
type Tab = 'consent' | 'demo' | 'dashboard' | 'audit';

export function App() {
  const [session, setSession] = useState<Session | null>(null);

  if (!session) {
    return <LoginFlow onLogin={setSession} />;
  }

  return <AuthenticatedApp session={session} onLogout={() => setSession(null)} />;
}

function AuthenticatedApp({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [tab, setTab] = useState<Tab>('consent');
  const [client] = useState(() => new ParticipantClient(session.participantRef));

  function handleLogout() {
    logout(session.token).finally(onLogout);
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
        <h1>BENEFITS - {session.participantRef}</h1>
        <button
          onClick={handleLogout}
          style={{
            marginLeft: 'auto',
            background: '#2f6fed',
            color: '#ffffff',
            border: 'none',
            borderRadius: '6px',
            padding: '0.5rem 1rem',
            cursor: 'pointer',
          }}
        >
          Log out
        </button>
      </div>
      <nav>
        <button className={tab === 'consent' ? 'active' : ''} onClick={() => setTab('consent')}>Consent</button>
        <button className={tab === 'demo' ? 'active' : ''} onClick={() => setTab('demo')}>Demo questionnaire</button>
        <button className={tab === 'dashboard' ? 'active' : ''} onClick={() => setTab('dashboard')}>Privacy dashboard</button>
        <button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}>Audit trail</button>
      </nav>
      {tab === 'consent' && <ConsentCards session={session} client={client} />}
      {tab === 'demo' && <DemoQuestionnaire session={session} client={client} />}
      {tab === 'dashboard' && <PrivacyDashboard session={session} client={client} />}
      {tab === 'audit' && <AuditViewer session={session} />}
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

  async function submitPassword(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await loginStep1(username, password);
      setMethods(result.methods);
      setMethod(result.methods[0] ?? 'totp');
      setStep(result.stage === 'enroll' ? 'enroll' : 'twofa');
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
      await requestOtp(username, password, method as 'email' | 'sms');
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
      const result = await verifySecondFactor(username, password, method, code);
      onLogin({ participantRef: result.username, token: result.token });
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
      const result = await enrollTotp(username, password);
      setOtpauth(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'enrollment failed');
    } finally {
      setBusy(false);
    }
  }

  if (step === 'enroll') {
    return (
      <form onSubmit={(e) => { e.preventDefault(); setMethods(['totp']); setMethod('totp'); setStep('twofa'); }}>
        <h1>BENEFITS - Participant</h1>
        <p>This account has no second factor yet. Set one up before continuing.</p>
        <button type="button" disabled={busy} onClick={setupTotp}>Set up authenticator app (TOTP)</button>
        {otpauth && (
          <div>
            <p>Scan this with your authenticator app, or enter the secret manually:</p>
            <p><code style={{ wordBreak: 'break-all' }}>{otpauth.otpauthUri}</code></p>
            <p>Secret: <code>{otpauth.secret}</code></p>
          </div>
        )}
        {otpauth && <button type="submit">I have added it, continue to code</button>}
        {error && <p>{error}</p>}
      </form>
    );
  }

  if (step === 'twofa') {
    return (
      <form onSubmit={submitTwoFactor}>
        <h1>BENEFITS - Participant</h1>
        <p>Second factor for {username}</p>
        <label>
          Method
          <select value={method} onChange={(e) => setMethod(e.target.value)}>
            {methods.map((m) => <option key={m} value={m}>{m === 'totp' ? 'Authenticator app (TOTP)' : m === 'email' ? 'Email code' : 'SMS code'}</option>)}
          </select>
        </label>
        {method !== 'totp' && (
          <button type="button" disabled={busy} onClick={sendCode}>Send code</button>
        )}
        <label>
          Code
          <input value={code} onChange={(e) => setCode(e.target.value)} required autoComplete="one-time-code" />
        </label>
        <button type="submit" disabled={busy}>Verify and continue</button>
        {error && <p>{error}</p>}
      </form>
    );
  }

  return (
    <form onSubmit={submitPassword}>
      <h1>BENEFITS - Participant</h1>
      <label>
        Participant ID
        <input value={username} onChange={(e) => setUsername(e.target.value)} required />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <button type="submit" disabled={busy}>Continue</button>
      {error && <p>{error}</p>}
    </form>
  );
}
