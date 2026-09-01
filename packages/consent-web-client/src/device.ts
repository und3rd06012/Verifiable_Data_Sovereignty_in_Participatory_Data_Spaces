import type { ConsentGrant, ContributionRecord } from '@benefits/consent-core';
import { createLocalDb } from './pouchdb';

// Local grant state lives in a plain array, not PouchDB -- a participant's
// own consent grants are small, always fully present locally (never
// partial), and revoke() needs to mutate one in place synchronously from
// the client's point of view. Contributed records go through PouchDB
// because they are what actually needs an offline queue and eventual
// replication.
const SENT_IDS_DOC_ID = '_local/sentIds';

interface SentIdsDoc {
  _id: string;
  _rev?: string;
  ids: string[];
}

export class ParticipantClient {
  private readonly db: PouchDB.Database;
  private localGrants: ConsentGrant[] = [];
  // Loaded from the `_local/sentIds` PouchDB doc on first use (see
  // loadSentIds()) -- `_local/` documents are excluded from PouchDB/CouchDB
  // replication, so this survives a page reload (unlike a plain in-memory
  // field) without ever leaking the sent-marker into the shared database.
  private sentIdsCache: Set<string> | null = null;

  constructor(private readonly participantRef: string) {
    this.db = createLocalDb(`participant-${participantRef}`);
  }

  private async loadSentIds(): Promise<Set<string>> {
    if (this.sentIdsCache) {
      return this.sentIdsCache;
    }
    try {
      const doc = await this.db.get<SentIdsDoc>(SENT_IDS_DOC_ID);
      this.sentIdsCache = new Set(doc.ids);
    } catch {
      this.sentIdsCache = new Set();
    }
    return this.sentIdsCache;
  }

  async grantConsent(grant: ConsentGrant): Promise<void> {
    this.localGrants.push(grant);
  }

  grants(): ConsentGrant[] {
    return this.localGrants;
  }

  async revoke(
    field: string, purpose: string, recipientClass: string, atIso: string
  ): Promise<{ sweptCount: number }> {
    const grant = this.localGrants.find(
      (g) => g.field === field && g.purpose === purpose && g.recipientClass === recipientClass
    );
    if (grant) {
      grant.revokedAt = atIso;
    }

    const unsent = await this.unsentRecords();
    let sweptCount = 0;
    for (const record of unsent) {
      if (Object.prototype.hasOwnProperty.call(record.fields, field)
          && record.declaredPurpose === purpose
          && record.declaredRecipientClass === recipientClass) {
        await this.db.remove(record._id!, record._rev!);
        sweptCount++;
      }
    }
    return { sweptCount };
  }

  async contribute(record: Omit<ContributionRecord, '_id'>): Promise<void> {
    // Namespaced by participantRef so a document-id collision across different
    // participants is structurally impossible: replication now fans every
    // participant's writes into the shared "benefits" database, where an
    // id collision would be a silent, invisible conflict rather than the loud
    // 409 it used to be when everyone wrote to that database directly.
    await this.db.put({
      ...record,
      _id: `contribution-${this.participantRef}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    });
  }

  async unsentRecords(): Promise<ContributionRecord[]> {
    const sentIds = await this.loadSentIds();
    const result = await this.db.allDocs({ include_docs: true });
    return result.rows
      .map((row) => row.doc as unknown as ContributionRecord)
      .filter((doc) => doc.type === 'contribution' && !sentIds.has(doc._id!));
  }

  async markSent(ids: string[]): Promise<void> {
    if (ids.length === 0) {
      return;
    }
    const sentIds = await this.loadSentIds();
    for (const id of ids) {
      sentIds.add(id);
    }
    // Purely local, single-writer state (never replicated), so a simple
    // get-then-put is sufficient -- no concurrent writer can race this.
    let rev: string | undefined;
    try {
      rev = (await this.db.get<SentIdsDoc>(SENT_IDS_DOC_ID))._rev;
    } catch {
      rev = undefined;
    }
    await this.db.put<SentIdsDoc>({ _id: SENT_IDS_DOC_ID, _rev: rev, ids: [...sentIds] });
  }

  localDb(): PouchDB.Database {
    return this.db;
  }
}
