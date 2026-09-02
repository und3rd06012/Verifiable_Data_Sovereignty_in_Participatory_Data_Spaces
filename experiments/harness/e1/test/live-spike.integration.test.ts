import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createCouchDbClient } from '../src/couchdb-client';
import { buildSignatureDesignDoc } from '../src/build-signature-design-doc';
import { generateSyntheticRecord } from '../src/synthetic-records';
import { base64Decode } from '../src/portable-codec';

const client = createCouchDbClient('http://localhost:5985', 'admin', 'benefits');
const DB = 'e1-spike';

describe.skipIf(!process.env.E1_LIVE)('signature design doc against real CouchDB', () => {
  beforeAll(async () => {
    await client.delete('/' + DB);
    const created = await client.put('/' + DB);
    expect([201, 202]).toContain(created.status);
    const dd = buildSignatureDesignDoc();
    const put = await client.put('/' + DB + '/' + dd._id, dd);
    expect([201, 202]).toContain(put.status);
  }, 30000);

  afterAll(async () => {
    await client.delete('/' + DB);
  });

  it('accepts a correctly signed, fully consented record', async () => {
    const record = generateSyntheticRecord({ seed: 100, index: 0, fieldCount: 2, grantSetSize: 2 });
    const res = await client.put('/' + DB + '/' + record._id, record);
    expect(res.status).toBe(201);
  });

  it('rejects a record with a tampered signature', async () => {
    const record = generateSyntheticRecord({ seed: 100, index: 1, fieldCount: 2, grantSetSize: 2 });
    const tamperedBytes = base64Decode(record.signature);
    tamperedBytes[0] = tamperedBytes[0] ^ 0xff;
    const tampered = { ...record, _id: record._id + '-tampered', signature: Buffer.from(tamperedBytes).toString('base64') };
    const res = await client.put('/' + DB + '/' + tampered._id, tampered);
    expect(res.status).toBe(403);
    expect(res.body.reason).toBe('invalid-signature');
  });

  it('rejects an unconsented field even with a valid signature', async () => {
    const record = generateSyntheticRecord({ seed: 100, index: 2, fieldCount: 2, grantSetSize: 2 });
    const unconsented = { ...record, _id: record._id + '-unconsented', consent: { ...record.consent, grants: [] } };
    const res = await client.put('/' + DB + '/' + unconsented._id, unconsented);
    expect(res.status).toBe(403);
    expect(res.body.reason).toMatch(/^no-grant:/);
  });
});
