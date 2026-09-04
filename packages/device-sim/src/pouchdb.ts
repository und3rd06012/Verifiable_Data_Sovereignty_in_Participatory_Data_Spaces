import PouchDB from 'pouchdb-core';
import memoryAdapter from 'pouchdb-adapter-memory';
import httpAdapter from 'pouchdb-adapter-http';
import replicationPlugin from 'pouchdb-replication';

PouchDB.plugin(memoryAdapter);
PouchDB.plugin(httpAdapter);
PouchDB.plugin(replicationPlugin);

// The in-memory adapter is explicit and required (spec section 3, Global
// Constraints), this is a fleet of simulated devices in a test
// environment, not real device storage, and pulling in a native
// LevelDB dependency (the default on Node for the batteries-included
// `pouchdb` package) would be pure cost for no benefit here.
export function createLocalDb(name: string): PouchDB.Database {
  return new PouchDB(name, { adapter: 'memory' });
}

export function createRemoteDb(url: string): PouchDB.Database {
  return new PouchDB(url);
}
