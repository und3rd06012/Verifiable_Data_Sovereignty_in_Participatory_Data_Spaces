package org.benefits.experiments.g1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full G1 sweep (spec section 3.3): the cross product of every swept value in
 * the given AvailabilitySweepConfig, one AvailabilitySweepPointRunner call per point
 * (each aggregating runsPerPoint independent seeded runs), written as a single CSV.
 */
public class AvailabilitySweepDriver {

    public void run(long seed, AvailabilitySweepConfig config, String csvPath, int runsPerPoint) throws IOException {
        var runner = new AvailabilitySweepPointRunner(seed);
        List<String> lines = new ArrayList<>();
        lines.add("seed,N,quorum_fraction,quorum_count,p_act,veto_share,runs,"
                + "arm_a_reached_rate,arm_a_starved_rate,arm_a_median_ticks_to_quorum,"
                + "arm_a_iqr_ticks_to_quorum,arm_b_reached_rate,arm_b_vetoed_rate,arm_b_starved_rate,"
                + "arm_b_median_ticks_to_quorum,arm_b_iqr_ticks_to_quorum");

        var pointIndex = 0;
        for (var n : config.nValues()) {
            for (var quorumFraction : config.quorumFractionValues()) {
                for (var pAct : config.pActValues()) {
                    for (var vetoShare : config.vetoShareValues()) {
                        var result = runner.run(n, quorumFraction, pAct, vetoShare, pointIndex, runsPerPoint);
                        lines.add(toCsvRow(result));
                        pointIndex++;
                    }
                }
            }
        }

        var outPath = Path.of(csvPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String toCsvRow(AvailabilitySweepPointResult result) {
        return String.join(",",
                Long.toString(result.seed()),
                Integer.toString(result.n()),
                Double.toString(result.quorumFraction()),
                Integer.toString(result.quorumCount()),
                Double.toString(result.pAct()),
                Double.toString(result.vetoShare()),
                Integer.toString(result.runs()),
                Double.toString(result.armAReachedRate()),
                Double.toString(result.armAStarvedRate()),
                result.armAMedianTicksToQuorum() == null ? "" : Double.toString(result.armAMedianTicksToQuorum()),
                result.armAIqrTicksToQuorum() == null ? "" : Double.toString(result.armAIqrTicksToQuorum()),
                Double.toString(result.armBReachedRate()),
                Double.toString(result.armBVetoedRate()),
                Double.toString(result.armBStarvedRate()),
                result.armBMedianTicksToQuorum() == null ? "" : Double.toString(result.armBMedianTicksToQuorum()),
                result.armBIqrTicksToQuorum() == null ? "" : Double.toString(result.armBIqrTicksToQuorum())
        );
    }
}
