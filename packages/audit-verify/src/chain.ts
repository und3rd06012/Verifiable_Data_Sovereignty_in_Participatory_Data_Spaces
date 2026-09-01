import { prevEpochHash, GENESIS_PREV_EPOCH_HASH } from './hashing.js';

export interface Epoch {
  epochId: string;
  merkleRoot: string;
  prevEpochHash: string;
  sealedAt: string;
}

// epochs must be oldest-first, matching AuditStore.epochChain's own ordering.
export async function verifyChain(epochs: Epoch[]): Promise<boolean> {
  let expectedPrev = await GENESIS_PREV_EPOCH_HASH;
  for (const epoch of epochs) {
    if (epoch.prevEpochHash !== expectedPrev) {
      return false;
    }
    expectedPrev = await prevEpochHash(epoch.epochId, epoch.merkleRoot, epoch.sealedAt);
  }
  return true;
}
