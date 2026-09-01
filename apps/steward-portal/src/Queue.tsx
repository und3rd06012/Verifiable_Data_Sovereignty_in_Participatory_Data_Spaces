import { useEffect, useState } from 'react';
import { STEWARD_API_BASE_URL } from './config';
import type { Session } from './App';

type StewardSignature = { stewardId: string; signature: string; signedAt: string };
type StewardVeto = { stewardId: string; reason: string; vetoedAt: string };
type StewardAppeal = { appellantId: string; reason: string; vetoStewardId: string; appealedAt: string };
type StewardAuthorization = {
  releaseRequestId: string; datasetHash: string;
  signatures: StewardSignature[]; vetoes: StewardVeto[]; appeals: StewardAppeal[];
} | null;
type ReleaseRequest = { releaseRequestId: string; purpose: string; recipientClass: string; fields: string[]; siteId: string };
type QueueEntry = { request: ReleaseRequest; authorization: StewardAuthorization };

function authHeader(session: Session): string {
  return 'Basic ' + btoa(`${session.stewardId}:${session.password}`);
}

export function Queue({ session }: { session: Session }) {
  const [entries, setEntries] = useState<QueueEntry[]>([]);
  const [status, setStatus] = useState('');

  async function load() {
    const response = await fetch(`${STEWARD_API_BASE_URL}/queue`, { headers: { authorization: authHeader(session) } });
    if (response.status === 403) {
      setStatus('Not authorized (is this a steward account?)');
      return;
    }
    setEntries(await response.json() as QueueEntry[]);
  }

  useEffect(() => { load(); }, []);

  async function act(requestId: string, action: 'co-sign' | 'veto', body: Record<string, unknown>) {
    await fetch(`${STEWARD_API_BASE_URL}/${encodeURIComponent(requestId)}/${action}`, {
      method: 'POST',
      headers: { authorization: authHeader(session), 'content-type': 'application/json' },
      body: JSON.stringify(body)
    });
    await load();
  }

  return (
    <div>
      <h2>Review queue</h2>
      <p>{status}</p>
      <ul>
        {entries.map((entry) => (
          <li key={entry.request.releaseRequestId}>
            <strong>{entry.request.releaseRequestId}</strong>: {entry.request.purpose} / {entry.request.recipientClass}
            {' '}(fields: {entry.request.fields.join(', ')}, site: {entry.request.siteId})
            <div>
              signatures: {entry.authorization?.signatures.length ?? 0}, vetoes: {entry.authorization?.vetoes.length ?? 0}
            </div>
            <button onClick={() => act(entry.request.releaseRequestId, 'co-sign', { datasetHash: 'hash-' + entry.request.releaseRequestId, signature: 'approved' })}>
              Co-sign
            </button>
            <button onClick={() => act(entry.request.releaseRequestId, 'veto', { datasetHash: 'hash-' + entry.request.releaseRequestId, reason: 'needs revision' })}>
              Veto
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
