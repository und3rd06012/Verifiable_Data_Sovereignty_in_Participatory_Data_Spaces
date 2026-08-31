import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildDesignDoc } from '../src/build-design-doc';

const BASE = 'http://localhost:5984';
const DB = 'benefits-conformance';
const vectorDir = join(__dirname, '../../../conformance/vectors');

// Node's native fetch (undici) implements the WHATWG Fetch spec, which
// forbids constructing a Request from a URL containing embedded credentials
// (http://admin:benefits@host). The credentials are sent as a Basic auth
// header instead; the account and password are unchanged.
const AUTH = 'Basic ' + Buffer.from('admin:benefits').toString('base64');

async function req(method: string, path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: AUTH },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

describe('CouchDB enforces S1', () => {
  beforeAll(async () => {
    await req('DELETE', `/${DB}`);
    const created = await req('PUT', `/${DB}`);
    expect([201, 202]).toContain(created.status);
    const dd = buildDesignDoc();
    const put = await req('PUT', `/${DB}/${dd._id}`, dd);
    expect([201, 202]).toContain(put.status);
  }, 30000);

  afterAll(async () => {
    await req('DELETE', `/${DB}`);
  });

  const files = readdirSync(vectorDir).filter((f) => f.endsWith('.json')).sort();

  files.forEach((file) => {
    const vector = JSON.parse(readFileSync(join(vectorDir, file), 'utf8'));
    it(`${vector.id} decided identically by CouchDB`, async () => {
      const res = await req('POST', `/${DB}`, { ...vector.record, _id: vector.id });
      if (vector.expected.allowed || vector.expected.reason === 'not-a-contribution') {
        expect(res.status).toBe(201);
      } else {
        expect(res.status).toBe(403);
        expect((res.body as { reason?: string }).reason).toBe(vector.expected.reason);
      }
    });
  });

  it('refuses a write whose consent assertion was stripped in transit', async () => {
    const vector = JSON.parse(readFileSync(join(vectorDir, 'v001-simple-allow.json'), 'utf8'));
    const tampered = { ...vector.record, _id: 'tampered', consent: undefined };
    const res = await req('POST', `/${DB}`, tampered);
    expect(res.status).toBe(403);
    expect((res.body as { reason?: string }).reason).toBe('missing-consent-assertion');
  });
});
