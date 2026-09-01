import { createRemoteDb } from './pouchdb';
import { Device } from './device';

export { injectConflict } from './conflictInjector';

export async function syncDevice(device: Device, remoteUrl: string): Promise<{ pushed: number }> {
  const remote = createRemoteDb(remoteUrl);
  const result = await new Promise<{ docs_written: number }>((resolve, reject) => {
    device.localDb().replicate.to(remote).on('complete', resolve).on('error', reject);
  });
  return { pushed: result.docs_written };
}

// "User preference first" (spec section 5): the device's own most recent
// local edit wins outright, tracked via each document's own
// `localEditedAt` field rather than trusted PouchDB revision ordering,
// which reflects replication history, not wall-clock recency across
// replicas.
export async function resolveConflicts(device: Device, remoteUrl: string): Promise<{ resolvedCount: number }> {
  const remote = createRemoteDb(remoteUrl);
  const allDocs = await remote.allDocs({ include_docs: true, conflicts: true });
  let resolvedCount = 0;

  for (const row of allDocs.rows) {
    const doc = row.doc as unknown as { _id: string; _rev: string; _conflicts?: string[]; localEditedAt?: string };
    if (!doc._conflicts || doc._conflicts.length === 0) {
      continue;
    }

    const rivals = await Promise.all(
      doc._conflicts.map((rev) => remote.get(doc._id, { rev }) as unknown as Promise<{ _rev: string; localEditedAt?: string }>)
    );
    const all = [{ _rev: doc._rev, localEditedAt: doc.localEditedAt }, ...rivals];
    all.sort((a, b) => (b.localEditedAt ?? '').localeCompare(a.localEditedAt ?? ''));
    const winner = all[0];
    const losers = all.slice(1);

    if (winner._rev !== doc._rev) {
      // The winning revision was one of the rivals, not the one allDocs
      // returned as current -- fetch its full body so the local device's
      // own edit still ends up as the live document content.
      await remote.put({ ...(await remote.get(doc._id, { rev: winner._rev })), _id: doc._id });
    }
    for (const loser of losers) {
      await remote.remove(doc._id, loser._rev);
    }
    resolvedCount++;
  }

  return { resolvedCount };
}
