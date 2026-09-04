import { useState } from 'react';
import { ParticipantClient, syncDevice, resolveConflicts } from '@benefits/consent-web-client';
import type { ConsentGrant } from '@benefits/consent-core';
import { GATEWAY_URL } from './config';
import { bearerFetch } from './auth';
import type { Session } from './App';

const PURPOSE = 'service-improvement';
const RECIPIENT_CLASS = 'statistics' as const;

type DemoField = {
  id: string;
  label: string;
  kind: 'select' | 'number';
  options?: string[];
};

const DEMO_FIELDS: DemoField[] = [
  { id: 'demo.employmentStatus', label: 'Employment status', kind: 'select', options: ['employed', 'unemployed', 'seasonal', 'student'] },
  { id: 'demo.housingNeed', label: 'Housing need', kind: 'select', options: ['none', 'temporary', 'permanent'] },
  { id: 'demo.healthNeed', label: 'Health need', kind: 'select', options: ['none', 'routine', 'urgent'] },
  { id: 'demo.serviceAccessed', label: 'Service accessed', kind: 'select', options: ['employment', 'healthcare', 'housing', 'legal'] },
  { id: 'demo.weeklyHours', label: 'Weekly service hours', kind: 'number' },
];

// Deliberately never granted: used to demonstrate the S1 refusal.
const SNEAK_FIELD = 'demo.income';

function hasGrant(grants: ConsentGrant[], field: string): boolean {
  return grants.some(
    (g) => g.field === field && g.purpose === PURPOSE && g.recipientClass === RECIPIENT_CLASS && !g.revokedAt
  );
}

export function DemoQuestionnaire({ session, client }: { session: Session; client: ParticipantClient }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [includeSneak, setIncludeSneak] = useState(false);
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);

  const remoteUrl = `${GATEWAY_URL}/couch/participant-${encodeURIComponent(session.participantRef)}`;

  const grants = client.grants();

  function setValue(field: string, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }));
  }

  async function toggleGrant(field: string, granted: boolean) {
    if (granted) {
      await client.grantConsent({
        field, purpose: PURPOSE, recipientClass: RECIPIENT_CLASS,
        grantedAt: new Date().toISOString(), expiresAt: null, revokedAt: null,
      });
    } else {
      await client.revoke(field, PURPOSE, RECIPIENT_CLASS, new Date().toISOString());
    }
    setStatus(`consent ${granted ? 'granted' : 'revoked'} for ${field}`);
  }

  function consentedValues(): Record<string, unknown> {
    const fields: Record<string, unknown> = {};
    for (const f of DEMO_FIELDS) {
      const value = values[f.id];
      if (value !== undefined && value !== '' && hasGrant(grants, f.id)) {
        fields[f.id] = f.kind === 'number' ? Number(value) : value;
      }
    }
    return fields;
  }

  async function queueContribution(withSneak: boolean) {
    setBusy(true);
    try {
      const fields = consentedValues();
      if (withSneak) {
        fields[SNEAK_FIELD] = Number(values[SNEAK_FIELD] ?? 1200);
      }
      await client.contribute({
        type: 'contribution',
        participantRef: session.participantRef,
        siteId: 'site-participant-web',
        collectedAt: new Date().toISOString(),
        channel: 'app',
        declaredPurpose: PURPOSE,
        declaredRecipientClass: RECIPIENT_CLASS,
        fields,
        consent: {
          participantRef: session.participantRef,
          grants: client.grants(),
          assertedAt: new Date().toISOString(),
        },
      });
      setStatus(
        withSneak
          ? `queued a contribution with ${SNEAK_FIELD} but no grant for it. Press "Sync now" to watch CouchDB refuse it.`
          : `queued ${Object.keys(fields).length} consented field(s). Press "Sync now" to push to CouchDB.`
      );
    } finally {
      setBusy(false);
    }
  }

  async function syncNow() {
    setBusy(true);
    setStatus('syncing...');
    try {
      const fetchImpl = bearerFetch(session.token);
      const { pushed } = await syncDevice(client, remoteUrl, fetchImpl);
      const { resolvedCount } = await resolveConflicts(client, remoteUrl, fetchImpl);
      setStatus(`sync ok: pushed ${pushed} record(s), resolved ${resolvedCount} conflict(s).`);
    } catch (err) {
      const detail = (err as { message?: string; reason?: string })?.reason
        ?? (err as { message?: string })?.message
        ?? String(err);
      setStatus(`sync error: ${detail}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h2>Demo questionnaire</h2>
      <p>
        Fill a few fields, grant consent per field, then sync. Everything stays local until you sync,
        and the database itself refuses any field you did not consent to share.
      </p>

      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Field</th>
            <th style={{ textAlign: 'left' }}>Value</th>
            <th style={{ textAlign: 'left' }}>Consent to share (statistics)</th>
          </tr>
        </thead>
        <tbody>
          {DEMO_FIELDS.map((f) => (
            <tr key={f.id}>
              <td>{f.label}<br /><small>{f.id}</small></td>
              <td>
                {f.kind === 'select' ? (
                  <select value={values[f.id] ?? ''} onChange={(e) => setValue(f.id, e.target.value)}>
                    <option value="">(not answered)</option>
                    {(f.options ?? []).map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                ) : (
                  <input
                    type="number"
                    value={values[f.id] ?? ''}
                    onChange={(e) => setValue(f.id, e.target.value)}
                    placeholder="e.g. 3"
                  />
                )}
              </td>
              <td>
                <input
                  type="checkbox"
                  checked={hasGrant(grants, f.id)}
                  onChange={(e) => toggleGrant(f.id, e.target.checked)}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div style={{ marginTop: '1rem', padding: '0.75rem', border: '1px dashed #c0392b' }}>
        <label>
          <input
            type="checkbox"
            checked={includeSneak}
            onChange={(e) => setIncludeSneak(e.target.checked)}
          />{' '}
          Include <code>{SNEAK_FIELD}</code> (never granted) to see S1 refuse the write
        </label>
      </div>

      <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
        <button disabled={busy} onClick={() => queueContribution(false)}>Queue contribution</button>
        <button disabled={busy} onClick={() => queueContribution(true)}>Queue with ungranted income field</button>
        <button disabled={busy} onClick={syncNow}>Sync now</button>
      </div>

      <p style={{ whiteSpace: 'pre-wrap' }}>{status}</p>
    </div>
  );
}
