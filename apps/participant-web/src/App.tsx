import { useState } from 'react';
import { ConsentCards } from './ConsentCards';
import { PrivacyDashboard } from './PrivacyDashboard';
import { AuditViewer } from './AuditViewer';

type Session = { participantRef: string; password: string };
type Tab = 'consent' | 'dashboard' | 'audit';

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>('consent');

  if (!session) {
    return <LoginForm onLogin={setSession} />;
  }

  return (
    <div>
      <nav>
        <button onClick={() => setTab('consent')}>Consent</button>
        <button onClick={() => setTab('dashboard')}>Privacy dashboard</button>
        <button onClick={() => setTab('audit')}>Audit trail</button>
      </nav>
      {tab === 'consent' && <ConsentCards session={session} />}
      {tab === 'dashboard' && <PrivacyDashboard session={session} />}
      {tab === 'audit' && <AuditViewer session={session} />}
    </div>
  );
}

function LoginForm({ onLogin }: { onLogin: (session: Session) => void }) {
  const [participantRef, setParticipantRef] = useState('');
  const [password, setPassword] = useState('');

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onLogin({ participantRef, password });
      }}
    >
      <label>
        Participant ID
        <input value={participantRef} onChange={(e) => setParticipantRef(e.target.value)} required />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <button type="submit">Continue</button>
    </form>
  );
}

export type { Session };
