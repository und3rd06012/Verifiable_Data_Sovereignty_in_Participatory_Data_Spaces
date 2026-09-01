import { useEffect, useState } from 'react';
import { ParticipantClient, syncDevice, resolveConflicts } from '@benefits/consent-web-client';
import type { ConsentGrant } from '@benefits/consent-core';
import { COUCHDB_BASE_URL } from './config';
import type { Session } from './App';

export function ConsentCards({ session }: { session: Session }) {
  const [client] = useState(() => new ParticipantClient(session.participantRef));
  const [grants, setGrants] = useState<ConsentGrant[]>([]);
  const [status, setStatus] = useState('');

  useEffect(() => {
    setGrants(client.grants());
  }, [client]);

  const remoteUrl = COUCHDB_BASE_URL.replace(
    '://', `://${encodeURIComponent(session.participantRef)}:${encodeURIComponent(session.password)}@`
  ) + '/benefits';

  async function grant(field: string, purpose: string, recipientClass: 'community' | 'provider' | 'statistics') {
    await client.grantConsent({
      field, purpose, recipientClass,
      grantedAt: new Date().toISOString(), expiresAt: null, revokedAt: null
    });
    setGrants([...client.grants()]);
  }

  async function revoke(field: string, purpose: string, recipientClass: string) {
    const { sweptCount } = await client.revoke(field, purpose, recipientClass, new Date().toISOString());
    setGrants([...client.grants()]);
    setStatus(`revoked (swept ${sweptCount} queued record${sweptCount === 1 ? '' : 's'} from the outbox)`);
  }

  async function sync() {
    setStatus('syncing...');
    const { pushed } = await syncDevice(client, remoteUrl);
    const { resolvedCount } = await resolveConflicts(client, remoteUrl);
    setStatus(`pushed ${pushed} record${pushed === 1 ? '' : 's'}, resolved ${resolvedCount} conflict${resolvedCount === 1 ? '' : 's'}`);
  }

  return (
    <div>
      <h2>Consent</h2>
      <button onClick={() => grant('service.satisfaction', 'service-improvement', 'statistics')}>
        Grant: satisfaction data for service improvement (statistics)
      </button>
      <ul>
        {grants.map((g, i) => (
          <li key={i}>
            {g.field} / {g.purpose} / {g.recipientClass}
            {g.revokedAt ? ' (revoked)' : (
              <button onClick={() => revoke(g.field, g.purpose, g.recipientClass)}>Revoke</button>
            )}
          </li>
        ))}
      </ul>
      <button onClick={sync}>Sync now</button>
      <p>{status}</p>
    </div>
  );
}
