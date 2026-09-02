package org.benefits.experiments.e3;

import org.benefits.audit.AuditHashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public class ChainFixtureRunner {

    private final long seed;

    public ChainFixtureRunner(long seed) {
        this.seed = seed;
    }

    public void run(List<Integer> chainLengths, String chainFixturesJsonPath) throws IOException {
        var out = new StringBuilder();
        out.append("[\n");
        var firstChain = true;

        for (var chainLength : chainLengths) {
            if (!firstChain) {
                out.append(",\n");
            }
            firstChain = false;
            appendChain(out, chainLength);
        }
        out.append("\n]\n");

        var outPath = Path.of(chainFixturesJsonPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendChain(StringBuilder out, int chainLength) {
        var random = new Random(seed * 1_000_003L + chainLength);
        out.append("  {\n");
        out.append("    \"chainLength\":").append(chainLength).append(",\n");
        out.append("    \"epochs\": [\n");

        var prevHash = AuditHashing.GENESIS_PREV_EPOCH_HASH;
        for (var i = 0; i < chainLength; i++) {
            var epochId = "epoch-" + chainLength + "-" + i;
            var merkleRoot = "root-" + Integer.toHexString(random.nextInt());
            var sealedAt = "2026-08-30T00:00:0" + (i % 10) + ".000Z";
            out.append("      { \"epochId\": \"").append(epochId)
                    .append("\", \"merkleRoot\": \"").append(merkleRoot)
                    .append("\", \"prevEpochHash\": \"").append(prevHash)
                    .append("\", \"sealedAt\": \"").append(sealedAt).append("\" }");
            out.append(i < chainLength - 1 ? ",\n" : "\n");
            prevHash = AuditHashing.prevEpochHash(epochId, merkleRoot, sealedAt);
        }

        out.append("    ]\n");
        out.append("  }");
    }
}
