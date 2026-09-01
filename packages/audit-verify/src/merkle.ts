import { nodeHash } from './hashing.js';

export interface ProofStep {
  siblingHash: string;
  siblingIsRight: boolean;
}

export interface MerkleProof {
  steps: ProofStep[];
}

// The mirror of MerkleTree.verify in connector/extensions/benefits-audit. Deliberately
// has no root()/proof() builder counterpart: the server (EpochSealer, AuditResource)
// is the only party that ever builds a tree; a participant device only ever verifies
// one it was handed.
export async function verifyInclusion(leaf: string, proof: MerkleProof, root: string): Promise<boolean> {
  let current = leaf;
  for (const step of proof.steps) {
    current = step.siblingIsRight
      ? await nodeHash(current, step.siblingHash)
      : await nodeHash(step.siblingHash, current);
  }
  return current === root;
}
