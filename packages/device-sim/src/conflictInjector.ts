import { randomBytes } from 'node:crypto';

// Deliberately bypasses Device/PouchDB entirely: a direct HTTP write to
// CouchDB, simulating "something else touched this document while the
// device was offline and unaware" without needing to simulate a second
// device (spec section 5's rejected alternative).
//
// Producing a genuine sibling conflict (rather than a normal update, or a
// write CouchDB simply rejects) needs `?new_edits=false` with a *fresh*
// revision id CouchDB has never seen, confirmed directly against a
// running CouchDB 3.5.2 instance (see task-7-report.md for the raw HTTP
// transcripts):
//
//   - A normal PUT always produces a linear update (or a 409 if the given
//     `_rev` is stale), CouchDB requires the new revision to descend from
//     the current one, so there is no way to fabricate a sibling this way.
//   - `?new_edits=false` bypasses that ancestry check: CouchDB inserts
//     whatever revision id you supply as a new leaf, with no requirement
//     that it be a descendant of anything already in the tree.
//   - Reusing the document's *existing* `_rev` verbatim (same generation,
//     same hash) is a no-op: CouchDB recognizes it as a revision it
//     already has and silently discards the new body, confirmed
//     empirically, this does NOT create a conflict.
//   - Supplying a brand-new random hash at generation `current + 1` (or
//     generation 1 if the document does not exist remotely yet, i.e.
//     `current` is 0) creates an unrelated branch with no shared ancestry
//     to the existing leaf. CouchDB has no way to reconcile the two, so
//     both become leaves of the revision tree, a genuine conflict,
//     reported in `_conflicts` exactly as PouchDB/CouchDB would report one
//     arising from two independent replicas.
export async function injectConflict(remoteUrl: string, docId: string, conflictingBody: object): Promise<void> {
  const url = new URL(remoteUrl);
  const auth = url.username && url.password
    ? 'Basic ' + Buffer.from(`${decodeURIComponent(url.username)}:${decodeURIComponent(url.password)}`).toString('base64')
    : undefined;
  url.username = '';
  url.password = '';
  const headers: Record<string, string> = auth ? { authorization: auth } : {};

  const docUrl = `${url.toString()}${url.pathname.endsWith('/') ? '' : '/'}${docId}`;
  const existing = await fetch(docUrl, { headers });
  const currentGeneration = existing.ok ? Number((await existing.json())._rev.split('-')[0]) : 0;
  const bogusRev = `${currentGeneration + 1}-${randomBytes(16).toString('hex')}`;

  const res = await fetch(`${docUrl}?new_edits=false`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify({ ...conflictingBody, _id: docId, _rev: bogusRev })
  });
  if (!res.ok) {
    throw new Error(`injectConflict failed: ${res.status} ${await res.text()}`);
  }
}
