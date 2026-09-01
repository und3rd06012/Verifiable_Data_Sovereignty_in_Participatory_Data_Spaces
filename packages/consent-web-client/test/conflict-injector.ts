// Test-only helper, local to this package (consent-web-client has no
// dependency on device-sim). Mirrors device-sim/src/conflictInjector.ts's
// approach exactly: a direct HTTP PUT with `?new_edits=false` at a fresh
// revision one generation above whatever CouchDB currently reports,
// producing a genuine sibling conflict without needing to simulate a
// second client.
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
  const bogusRev = `${currentGeneration + 1}-${Math.random().toString(16).slice(2, 34).padEnd(32, '0')}`;

  const res = await fetch(`${docUrl}?new_edits=false`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify({ ...conflictingBody, _id: docId, _rev: bogusRev })
  });
  if (!res.ok) {
    throw new Error(`injectConflict failed: ${res.status} ${await res.text()}`);
  }
}
