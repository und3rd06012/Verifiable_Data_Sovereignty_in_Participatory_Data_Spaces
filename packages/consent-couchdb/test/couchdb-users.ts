const BASE = 'http://localhost:5984';
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:benefits').toString('base64');

let usersDbInitialized = false;

async function ensureUsersDatabaseExists(): Promise<void> {
  if (usersDbInitialized) return;
  const res = await fetch(`${BASE}/_users`, {
    method: 'PUT',
    headers: { authorization: ADMIN_AUTH, 'content-type': 'application/json' }
  });
  if (res.status !== 201 && res.status !== 412) {
    throw new Error(`failed to create _users database: ${res.status}`);
  }
  usersDbInitialized = true;
}

export function basicAuth(name: string, password: string): string {
  return 'Basic ' + Buffer.from(`${name}:${password}`).toString('base64');
}

export async function ensureCouchDbUser(name: string, password: string, roles: string[] = []): Promise<void> {
  await ensureUsersDatabaseExists();
  const res = await fetch(`${BASE}/_users/org.couchdb.user:${name}`, {
    method: 'PUT',
    headers: { authorization: ADMIN_AUTH, 'content-type': 'application/json' },
    body: JSON.stringify({ name, password, roles, type: 'user' })
  });
  if (res.status !== 201 && res.status !== 409) {
    throw new Error(`failed to provision CouchDB user ${name}: ${res.status}`);
  }
}
