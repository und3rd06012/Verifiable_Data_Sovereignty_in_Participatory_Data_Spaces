import { createRemoteDb } from './pouchdb.js';
import { Device } from './device.js';

export { injectConflict } from './conflictInjector.js';

export async function syncDevice(device: Device, remoteUrl: string): Promise<{ pushed: number }> {
  const remote = createRemoteDb(remoteUrl);
  const pending = await device.unsentRecords();
  const result = await new Promise<{ docs_written: number }>((resolve, reject) => {
    device.localDb().replicate.to(remote).on('complete', resolve).on('error', reject);
  });
  device.markSent(pending.map((r) => r._id!));
  return { pushed: result.docs_written };
}

// "User preference first" (spec section 5), made precise there after an
// earlier draft of this function conflated it with last-write-wins:
// identity, not recency, decides. The device's own CURRENT local copy of
// the document always wins, unconditionally -- never compared by
// timestamp against a rival, since a rival's claimed edit time cannot be
// trusted (every rival in this design is a harness injection, never a
// second genuine user choice). This also sidesteps needing to trust
// PouchDB's own revision ordering, which reflects replication history,
// not wall-clock recency, and is no more trustworthy a signal than a
// rival's self-reported timestamp.
export async function resolveConflicts(device: Device, remoteUrl: string): Promise<{ resolvedCount: number }> {
  const remote = createRemoteDb(remoteUrl);
  const allDocs = await remote.allDocs({ include_docs: true, conflicts: true });
  let resolvedCount = 0;

  for (const row of allDocs.rows) {
    const doc = row.doc as unknown as { _id: string; _rev: string; _conflicts?: string[] };
    if (!doc._conflicts || doc._conflicts.length === 0) {
      continue;
    }

    let localDoc: PouchDB.Core.ExistingDocument<Record<string, unknown>>;
    try {
      localDoc = await device.localDb().get(doc._id);
    } catch {
      continue; // device has no local copy of this document; nothing to prefer
    }

    // Re-write the remote document fresh from the device's own local
    // content, as a new revision descending from whichever revision
    // CouchDB currently reports as "current" (`doc._rev`). A plain PUT
    // requires *some* real ancestor to avoid a 409 -- confirmed
    // empirically that omitting `_rev` entirely 409s against a document
    // that already exists, so `doc._rev` (always a valid, real revision at
    // this point) is supplied deliberately; which existing branch it
    // descends from doesn't matter for correctness, since it is the
    // content -- the device's own -- that must win, not the ancestry.
    // Superseding `doc._rev` this way means it is no longer a leaf, so it
    // does not need (and cannot correctly receive) an explicit delete;
    // every OTHER conflicting revision remains an independent branch and
    // is deleted explicitly below to converge the document.
    const { _id, _rev, ...content } = localDoc as unknown as { _id: string; _rev: string; [key: string]: unknown };
    await remote.put({ _id: doc._id, _rev: doc._rev, ...content });

    for (const rev of doc._conflicts) {
      await remote.remove(doc._id, rev);
    }
    resolvedCount++;
  }

  return { resolvedCount };
}
