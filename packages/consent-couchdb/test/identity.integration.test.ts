import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { buildDesignDoc } from '../src/build-design-doc';
import { basicAuth, ensureCouchDbUser } from './couchdb-users';

const BASE = 'http://localhost:5984';
const DB = 'benefits-identity-test';
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:benefits').toString('base64');

let couchDbReachable = false;
try {
  await fetch(BASE);
  couchDbReachable = true;
} catch {
  couchDbReachable = false;
}

async function req(auth: string, method: string, path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: auth },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

function contribution(participantRef: string, id: string) {
  return {
    _id: id,
    type: 'contribution',
    participantRef,
    siteId: 'athens',
    collectedAt: '2026-09-01T00:00:00.000Z',
    channel: 'app',
    declaredPurpose: 'service-improvement',
    declaredRecipientClass: 'statistics',
    fields: { 'service.satisfaction': 4 },
    consent: {
      participantRef,
      assertedAt: '2026-08-01T00:00:00.000Z',
      grants: [{
        field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
        grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
      }]
    }
  };
}

describe.skipIf(!couchDbReachable)('participant-identity write enforcement', () => {
  beforeAll(async () => {
    await req(ADMIN_AUTH, 'DELETE', `/${DB}`);
    await req(ADMIN_AUTH, 'PUT', `/${DB}`);

    // Set database security to allow any authenticated user to read/write
    await req(ADMIN_AUTH, 'PUT', `/${DB}/_security`, {
      members: { names: [], roles: [] }
    });

    const dd = buildDesignDoc();
    await req(ADMIN_AUTH, 'PUT', `/${DB}/${dd._id}`, dd);
    await ensureCouchDbUser('p-identity-a', 'pw-a', []);
    await ensureCouchDbUser('p-identity-b', 'pw-b', []);
  }, 30000);

  afterAll(async () => {
    await req(ADMIN_AUTH, 'DELETE', `/${DB}`);
  });

  it('rejects a write whose authenticated user does not match participantRef', async () => {
    const auth = basicAuth('p-identity-a', 'pw-a');
    const res = await req(auth, 'PUT', `/${DB}/doc-1`, contribution('p-identity-b', 'doc-1'));
    expect(res.status).toBe(403);
  });

  it('accepts a write whose authenticated user matches participantRef', async () => {
    const auth = basicAuth('p-identity-a', 'pw-a');
    const res = await req(auth, 'PUT', `/${DB}/doc-2`, contribution('p-identity-a', 'doc-2'));
    expect(res.status).toBe(201);
  });

  it('admin-authenticated writes are exempt from the identity check', async () => {
    const res = await req(ADMIN_AUTH, 'PUT', `/${DB}/doc-3`, contribution('someone-else-entirely', 'doc-3'));
    expect(res.status).toBe(201);
  });

  it('rejects deleting a document owned by a different participant', async () => {
    const authA = basicAuth('p-identity-a', 'pw-a');
    const authB = basicAuth('p-identity-b', 'pw-b');
    const created = await req(authA, 'PUT', `/${DB}/doc-4`, contribution('p-identity-a', 'doc-4'));
    expect(created.status).toBe(201);
    const rev = (created.body as { rev: string }).rev;
    const res = await req(authB, 'DELETE', `/${DB}/doc-4?rev=${rev}`);
    expect(res.status).toBe(403);
  });

  it('accepts deleting a document owned by the authenticated participant', async () => {
    const authA = basicAuth('p-identity-a', 'pw-a');
    const created = await req(authA, 'PUT', `/${DB}/doc-5`, contribution('p-identity-a', 'doc-5'));
    expect(created.status).toBe(201);
    const rev = (created.body as { rev: string }).rev;
    const res = await req(authA, 'DELETE', `/${DB}/doc-5?rev=${rev}`);
    expect(res.status).toBe(200);
  });
});
