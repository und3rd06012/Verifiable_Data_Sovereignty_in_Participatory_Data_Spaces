import PouchDB from 'pouchdb-core';
import idbAdapter from 'pouchdb-adapter-idb';
import httpAdapter from 'pouchdb-adapter-http';
import replicationPlugin from 'pouchdb-replication';

PouchDB.plugin(idbAdapter);
PouchDB.plugin(httpAdapter);
PouchDB.plugin(replicationPlugin);

// The idb adapter is the real browser persistence layer -- this is the
// actual participant client, not a simulation (device-sim's memory
// adapter is deliberately test-only, spec section 3 of the phase 5
// design). IndexedDB survives page reloads within one browser profile,
// which is the property the privacy dashboard and offline outbox both
// depend on.
export function createLocalDb(name: string): PouchDB.Database {
  return new PouchDB(name, { adapter: 'idb' });
}

export function createRemoteDb(url: string): PouchDB.Database {
  return new PouchDB(url);
}
