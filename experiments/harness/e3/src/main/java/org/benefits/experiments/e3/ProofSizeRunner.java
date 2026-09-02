package org.benefits.experiments.e3;

import org.benefits.audit.AuditHashing;
import org.benefits.audit.MerkleProof;
import org.benefits.audit.MerkleTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures proof size for a swept set of epoch sizes and, for the same sampled
 * points, exports a JSON fixture file so experiments/harness/e3-verify (Node) can
 * time verifyInclusion() against byte-identical data without needing to reimplement
 * tree construction itself.
 */
public class ProofSizeRunner {

    private final long seed;

    public ProofSizeRunner(long seed) {
        this.seed = seed;
    }

    public void run(List<Integer> epochSizes, String proofSizeCsvPath, String inclusionFixturesJsonPath) throws IOException {
        var csvLines = new ArrayList<String>();
        csvLines.add("seed,epoch_size,leaf_index,proof_steps,proof_bytes");

        var fixtureJson = new StringBuilder();
        fixtureJson.append("[\n");
        var firstFixture = true;

        for (var epochSize : epochSizes) {
            var entries = new SeededFixtureGenerator(seed).entriesForEpoch(epochSize, "epoch-" + epochSize);
            var leafHashes = new ArrayList<String>();
            for (var entry : entries) {
                leafHashes.add(AuditHashing.leafHash(entry));
            }
            var root = MerkleTree.root(leafHashes);

            for (var leafIndex : sampleIndices(epochSize)) {
                var proof = MerkleTree.proof(leafHashes, leafIndex);
                var steps = proof.steps().size();
                var bytes = steps * 32;
                csvLines.add(seed + "," + epochSize + "," + leafIndex + "," + steps + "," + bytes);

                if (!firstFixture) {
                    fixtureJson.append(",\n");
                }
                firstFixture = false;
                appendFixture(fixtureJson, epochSize, leafIndex, leafHashes.get(leafIndex), proof, root);
            }
        }
        fixtureJson.append("\n]\n");

        var csvOutPath = Path.of(proofSizeCsvPath);
        var jsonOutPath = Path.of(inclusionFixturesJsonPath);
        Files.createDirectories(csvOutPath.toAbsolutePath().getParent());
        Files.createDirectories(jsonOutPath.toAbsolutePath().getParent());
        Files.write(csvOutPath, String.join("\n", csvLines).concat("\n").getBytes(StandardCharsets.UTF_8));
        Files.write(jsonOutPath, fixtureJson.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Integer> sampleIndices(int epochSize) {
        List<Integer> indices = new ArrayList<>();
        if (epochSize <= 10) {
            for (var i = 0; i < epochSize; i++) {
                indices.add(i);
            }
            return indices;
        }
        for (var i = 0; i < 10; i++) {
            indices.add((int) Math.floor((long) i * (epochSize - 1) / 9.0));
        }
        return indices;
    }

    private static void appendFixture(
            StringBuilder out, int epochSize, int leafIndex, String leaf, MerkleProof proof, String root) {
        out.append("  {\n");
        out.append("    \"epochSize\":").append(epochSize).append(",\n");
        out.append("    \"leafIndex\": ").append(leafIndex).append(",\n");
        out.append("    \"leaf\": \"").append(leaf).append("\",\n");
        out.append("    \"root\": \"").append(root).append("\",\n");
        out.append("    \"steps\": [\n");
        var steps = proof.steps();
        for (var i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            out.append("      { \"siblingHash\": \"").append(step.siblingHash())
                    .append("\", \"siblingIsRight\": ").append(step.siblingIsRight()).append(" }");
            out.append(i < steps.size() - 1 ? ",\n" : "\n");
        }
        out.append("    ]\n");
        out.append("  }");
    }
}
