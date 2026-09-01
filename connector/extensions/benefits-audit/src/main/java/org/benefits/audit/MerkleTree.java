package org.benefits.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * A pairwise Merkle tree with domain-separated leaf and internal-node hashes
 * (AuditHashing), so an internal node's hash can never be replayed as a leaf's. An
 * odd node at any level is promoted to the next level unhashed rather than
 * duplicated, avoiding the CVE-2012-2459 duplicate-leaf ambiguity. root() and
 * proof() must stay in lockstep: both walk levels using the exact same pairing and
 * promotion rule, or a proof built by one will not verify against a root built by
 * the other.
 */
public final class MerkleTree {

    private MerkleTree() {
    }

    public static String root(List<String> leafHashes) {
        if (leafHashes.isEmpty()) {
            throw new IllegalArgumentException("cannot compute a root over zero leaves");
        }
        var level = leafHashes;
        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return level.get(0);
    }

    public static MerkleProof proof(List<String> leafHashes, int index) {
        if (index < 0 || index >= leafHashes.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " out of range for " + leafHashes.size() + " leaves");
        }
        List<MerkleProof.ProofStep> steps = new ArrayList<>();
        var level = leafHashes;
        var position = index;
        while (level.size() > 1) {
            var isLastInLevel = position == level.size() - 1;
            var isOdd = level.size() % 2 == 1;
            if (!(isOdd && isLastInLevel)) {
                var siblingOnRight = position % 2 == 0;
                var siblingIndex = siblingOnRight ? position + 1 : position - 1;
                steps.add(new MerkleProof.ProofStep(level.get(siblingIndex), siblingOnRight));
            }
            level = nextLevel(level);
            position = position / 2;
        }
        return new MerkleProof(steps);
    }

    public static boolean verify(String leafHash, MerkleProof proof, String root) {
        var current = leafHash;
        for (var step : proof.steps()) {
            current = step.siblingIsRight()
                    ? AuditHashing.nodeHash(current, step.siblingHash())
                    : AuditHashing.nodeHash(step.siblingHash(), current);
        }
        return current.equals(root);
    }

    private static List<String> nextLevel(List<String> level) {
        List<String> next = new ArrayList<>();
        var i = 0;
        while (i + 1 < level.size()) {
            next.add(AuditHashing.nodeHash(level.get(i), level.get(i + 1)));
            i += 2;
        }
        if (i < level.size()) {
            next.add(level.get(i));
        }
        return next;
    }
}
