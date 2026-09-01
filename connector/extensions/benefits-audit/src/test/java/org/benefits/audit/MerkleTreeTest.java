package org.benefits.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerkleTreeTest {

    private static List<String> leaves(int count) {
        List<String> result = new ArrayList<>();
        for (var i = 0; i < count; i++) {
            var entry = new AuditEntry(i, "epoch-1", "2026-08-31T00:00:00Z", "type", "actor",
                    "asset-" + i, "purpose", "plain-" + i);
            result.add(AuditHashing.leafHash(entry));
        }
        return result;
    }

    @Test
    void singleLeafRootIsItsOwnLeafHash() {
        var leaves = leaves(1);
        assertThat(MerkleTree.root(leaves)).isEqualTo(leaves.get(0));
    }

    @Test
    void rootRejectsZeroLeaves() {
        assertThatThrownBy(() -> MerkleTree.root(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyIndexVerifiesAgainstTheRootAtEveryParityFromOneToTwelve() {
        for (var count = 1; count <= 12; count++) {
            var leaves = leaves(count);
            var root = MerkleTree.root(leaves);
            for (var index = 0; index < count; index++) {
                var proof = MerkleTree.proof(leaves, index);
                assertThat(MerkleTree.verify(leaves.get(index), proof, root))
                        .as("count=%d index=%d", count, index)
                        .isTrue();
            }
        }
    }

    @Test
    void aWrongLeafDoesNotVerify() {
        var leaves = leaves(5);
        var root = MerkleTree.root(leaves);
        var proof = MerkleTree.proof(leaves, 2);
        assertThat(MerkleTree.verify(leaves.get(3), proof, root)).isFalse();
    }

    @Test
    void aMutatedRootDoesNotVerify() {
        var leaves = leaves(5);
        var proof = MerkleTree.proof(leaves, 2);
        assertThat(MerkleTree.verify(leaves.get(2), proof, "0".repeat(64))).isFalse();
    }

    @Test
    void aTamperedProofStepDoesNotVerify() {
        var leaves = leaves(5);
        var root = MerkleTree.root(leaves);
        var proof = MerkleTree.proof(leaves, 2);
        var tampered = new MerkleProof(List.of(
                new MerkleProof.ProofStep("f".repeat(64), proof.steps().get(0).siblingIsRight())));
        assertThat(MerkleTree.verify(leaves.get(2), tampered, root)).isFalse();
    }

    @Test
    void proofRejectsAnOutOfRangeIndex() {
        var leaves = leaves(3);
        assertThatThrownBy(() -> MerkleTree.proof(leaves, 3)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
