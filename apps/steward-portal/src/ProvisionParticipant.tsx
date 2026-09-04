import { useState } from 'react';
import { GATEWAY_URL } from './config';
import type { Session } from './App';

export function ProvisionParticipant({ session }: { session: Session }) {
  const [participantRef, setParticipantRef] = useState('');
  const [result, setResult] = useState<{ participantRef: string; password: string } | null>(null);

  async function provision(e: React.FormEvent) {
    e.preventDefault();
    const auth = 'Bearer ' + session.token;
    const response = await fetch(`${GATEWAY_URL}/steward/participants/${encodeURIComponent(participantRef)}/provision`, {
      method: 'POST',
      headers: { authorization: auth }
    });
    setResult(await response.json() as { participantRef: string; password: string });
  }

  return (
    <div>
      <h2>Provision a participant</h2>
      <form onSubmit={provision}>
        <label>
          Participant ID
          <input value={participantRef} onChange={(e) => setParticipantRef(e.target.value)} required />
        </label>
        <button type="submit">Provision</button>
      </form>
      {result && (
        <p>
          Give these to the participant once, out of band -- they will not be shown again:
          ID <code>{result.participantRef}</code>, password <code>{result.password}</code>
        </p>
      )}
    </div>
  );
}
