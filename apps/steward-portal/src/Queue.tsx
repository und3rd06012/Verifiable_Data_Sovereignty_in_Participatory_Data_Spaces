import { useEffect, useState } from 'react';
import { GATEWAY_URL } from './config';
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
  return 'Bearer ' + session.token;
}

export function Queue({ session }: { session: Session }) {
  const [entries, setEntries] = useState<QueueEntry[]>([]);
  const [status, setStatus] = useState('');

  async function load() {
    const response = await fetch(`${GATEWAY_URL}/steward/queue`, { headers: { authorization: authHeader(session) } });
    if (response.status === 403) {
      setStatus('Not authorized (is this a steward account?)');
      return;
    }
    setEntries(await response.json() as QueueEntry[]);
  }

  useEffect(() => { load(); }, []);

  async function act(requestId: string, action: 'co-sign' | 'veto' | 'review' | 'appeal', body: Record<string, unknown>) {
    await fetch(`${GATEWAY_URL}/steward/${encodeURIComponent(requestId)}/${action}`, {
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
            <form onSubmit={(e) => {
              e.preventDefault();
              const formData = new FormData(e.currentTarget);
              const rubricScore = Number(formData.get('rubricScore'));
              const notes = String(formData.get('notes') ?? '');
              act(entry.request.releaseRequestId, 'review', { datasetHash: 'hash-' + entry.request.releaseRequestId, rubricScore, notes });
              e.currentTarget.reset();
            }}>
              <input name="rubricScore" type="number" placeholder="score" required />
              <input name="notes" type="text" placeholder="notes" required />
              <button type="submit">Review</button>
            </form>
            {entry.authorization && entry.authorization.vetoes.length > 0 && (
              <form onSubmit={(e) => {
                e.preventDefault();
                const formData = new FormData(e.currentTarget);
                const vetoStewardId = String(formData.get('vetoStewardId') ?? '');
                const reason = String(formData.get('reason') ?? '');
                act(entry.request.releaseRequestId, 'appeal', { vetoStewardId, reason });
                e.currentTarget.reset();
              }}>
                <select name="vetoStewardId" required>
                  {entry.authorization.vetoes.map((veto) => (
                    <option key={veto.stewardId} value={veto.stewardId}>{veto.stewardId} ({veto.reason})</option>
                  ))}
                </select>
                <input name="reason" type="text" placeholder="appeal reason" required />
                <button type="submit">Appeal</button>
              </form>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
