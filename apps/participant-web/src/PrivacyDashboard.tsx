import { useEffect, useState } from 'react';
import { ParticipantClient } from '@benefits/consent-web-client';
import type { ConsentGrant } from '@benefits/consent-core';
import type { Session } from './App';

export function PrivacyDashboard({ session, client }: { session: Session; client: ParticipantClient }) {
  const [grants, setGrants] = useState<ConsentGrant[]>([]);

  useEffect(() => {
    setGrants([...client.grants()]);
  }, [client]);

  const active = grants.filter((g) => !g.revokedAt && (!g.expiresAt || g.expiresAt > new Date().toISOString()));

  return (
    <div>
      <h2>Your active grants</h2>
      <p>{active.length} active grant{active.length === 1 ? '' : 's'} out of {grants.length} total.</p>
      <ul>
        {active.map((g, i) => (
          <li key={i}>{g.field} shared for {g.purpose} with {g.recipientClass}, since {g.grantedAt}</li>
        ))}
      </ul>
    </div>
  );
}
