package org.benefits.audit;

import java.util.List;

/**
 * An ordered list of sibling hashes a verifier combines with a leaf hash, each tagged
 * with which side the sibling sits on, so the verifier never needs to know the tree's
 * shape or the leaf's index.
 */
public record MerkleProof(List<ProofStep> steps) {

    public record ProofStep(String siblingHash, boolean siblingIsRight) {
    }
}
