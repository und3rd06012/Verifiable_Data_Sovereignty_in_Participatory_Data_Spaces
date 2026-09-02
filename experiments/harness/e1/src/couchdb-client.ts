// Mirrors the req() helper in packages/consent-couchdb/test/couchdb.integration.test.ts.
// Node's native fetch forbids embedded credentials in the URL, so Basic auth goes in
// a header instead.

export interface CouchDbResponse {
  status: number;
  body: any;
}

export interface CouchDbClient {
  put(path: string, body?: unknown): Promise<CouchDbResponse>;
  post(path: string, body?: unknown): Promise<CouchDbResponse>;
  delete(path: string): Promise<CouchDbResponse>;
}

export function createCouchDbClient(baseUrl: string, adminUser: string, adminPassword: string): CouchDbClient {
  const auth = 'Basic ' + Buffer.from(adminUser + ':' + adminPassword).toString('base64');

  async function request(method: string, path: string, body?: unknown): Promise<CouchDbResponse> {
    const res = await fetch(baseUrl + path, {
      method,
      headers: { 'content-type': 'application/json', authorization: auth },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const parsed = await res.json().catch(() => ({}));
    return { status: res.status, body: parsed };
  }

  return {
    put: (path, body) => request('PUT', path, body),
    post: (path, body) => request('POST', path, body),
    delete: (path) => request('DELETE', path)
  };
}
