import type { ConsentGrant, ContributionRecord } from '@benefits/consent-core';
import { createLocalDb } from './pouchdb';

// Local grant state lives in a plain array, not PouchDB -- a device's own
// consent grants are small, always fully present locally (never partial),
// and revoke() needs to mutate one in place synchronously from the
// device's point of view. Contributed records go through PouchDB because
// they are what actually needs an offline queue and eventual replication.
export class Device {
  private readonly db: PouchDB.Database;
  private localGrants: ConsentGrant[] = [];

  constructor(private readonly participantRef: string) {
    this.db = createLocalDb(`device-${participantRef}-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  }

  async grantConsent(grant: ConsentGrant): Promise<void> {
    this.localGrants.push(grant);
  }

  grants(): ConsentGrant[] {
    return this.localGrants;
  }

  async revoke(field: string, purpose: string, recipientClass: string, atIso: string): Promise<void> {
    const grant = this.localGrants.find(
      (g) => g.field === field && g.purpose === purpose && g.recipientClass === recipientClass
    );
    if (grant) {
      grant.revokedAt = atIso;
    }
  }

  async contribute(record: Omit<ContributionRecord, '_id'>): Promise<void> {
    await this.db.put({ ...record, _id: `contribution-${Date.now()}-${Math.random().toString(36).slice(2)}` });
  }

  async unsentRecords(): Promise<ContributionRecord[]> {
    const result = await this.db.allDocs({ include_docs: true });
    return result.rows
      .map((row) => row.doc as unknown as ContributionRecord)
      .filter((doc) => doc.type === 'contribution');
  }

  localDb(): PouchDB.Database {
    return this.db;
  }
}
