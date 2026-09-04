import { useState } from 'react';
import { GATEWAY_URL } from './config';
import type { Session } from './App';

type AuditEntry = { seq: number; timestamp: string; eventType: string; datasetRef: string; plainLanguage: string };
type AuditResponse = { entries: AuditEntry[] };

export function AuditViewer({ session }: { session: Session }) {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState('');

  async function load() {
    setError('');
    const response = await fetch(`${GATEWAY_URL}/audit/${encodeURIComponent(session.participantRef)}`, {
      headers: { authorization: `Bearer ${session.token}` }
    });
    if (response.status === 403) {
      setError('Not authorized to view this audit trail.');
      return;
    }
    const body = await response.json() as AuditResponse;
    setEntries(body.entries);
  }

  return (
    <div>
      <h2>Audit trail</h2>
      <button onClick={load}>Load</button>
      {error && <p>{error}</p>}
      {entries && (
        <ul>
          {entries.map((e) => (
            <li key={e.seq}>{e.timestamp}: {e.plainLanguage}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
