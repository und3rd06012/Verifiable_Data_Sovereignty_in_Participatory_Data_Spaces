import { useState } from 'react';
import { Queue } from './Queue';
import { ProvisionParticipant } from './ProvisionParticipant';

export type Session = { stewardId: string; password: string };
type Tab = 'queue' | 'provision';

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>('queue');

  if (!session) {
    return <LoginForm onLogin={setSession} />;
  }

  return (
    <div>
      <nav>
        <button onClick={() => setTab('queue')}>Review queue</button>
        <button onClick={() => setTab('provision')}>Provision participant</button>
      </nav>
      {tab === 'queue' && <Queue session={session} />}
      {tab === 'provision' && <ProvisionParticipant session={session} />}
    </div>
  );
}

function LoginForm({ onLogin }: { onLogin: (session: Session) => void }) {
  const [stewardId, setStewardId] = useState('');
  const [password, setPassword] = useState('');

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onLogin({ stewardId, password });
      }}
    >
      <label>
        Steward ID
        <input value={stewardId} onChange={(e) => setStewardId(e.target.value)} required />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <button type="submit">Continue</button>
    </form>
  );
}
