package org.benefits.experiments.g1;

import org.benefits.policy.InMemoryStewardAuthorizationStore;
import org.benefits.policy.StewardQuorumFunction;
import org.benefits.policy.StewardSignature;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

import java.time.Instant;
import java.util.Map;
import java.util.Random;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Runs one simulated steward-availability timeline (spec section 3.2): a pool of N
 * stewards, each not-yet-acted steward independently acting each tick with probability
 * p_act, driving the real StewardQuorumFunction/InMemoryStewardAuthorizationStore
 * (Arm A, spec section 3.1). Arm B (the hypothetical veto-blocks-quorum arm) is added
 * in Task 4.
 */
public class AvailabilityTimelineRunner {

    public static final int MAX_TICKS = 200;

    private static final String RELEASE_REQUEST_ID = "g1-run";
    private static final String DATASET_HASH = "g1-hash";

    public RunOutcome run(int n, int quorum, double pAct, double vetoShare, long seed) {
        var random = new Random(seed);
        var store = new InMemoryStewardAuthorizationStore();
        var quorumFunction = new StewardQuorumFunction(store, new Monitor() {
        });
        var permission = permissionFor(RELEASE_REQUEST_ID);

        var acted = new boolean[n];
        Integer armATicks = null;

        for (var tick = 1; tick <= MAX_TICKS; tick++) {
            for (var s = 0; s < n; s++) {
                if (acted[s]) {
                    continue;
                }
                if (random.nextDouble() < pAct) {
                    acted[s] = true;
                    // A veto is recorded here in the store even though Task 3's own Arm
                    // A logic below never reads .vetoes() at all, matching the real
                    // StewardQuorumFunction's behavior exactly. This keeps the random
                    // draw sequence and the store's real state identical between Task 3
                    // and Task 4: Task 4 only adds a second reader of this same,
                    // already-correct state (Arm B's veto/quorum check), and does not
                    // change what gets appended or when, so Task 3's own committed test
                    // results stay valid unchanged after Task 4 extends this method.
                    var stewardId = "steward-" + s;
                    if (random.nextDouble() < vetoShare) {
                        store.appendVeto(RELEASE_REQUEST_ID, DATASET_HASH,
                                new org.benefits.policy.StewardVeto(stewardId, "simulated", Instant.EPOCH));
                    } else {
                        store.appendSignature(RELEASE_REQUEST_ID, DATASET_HASH,
                                new StewardSignature(stewardId, "sig-" + stewardId, Instant.EPOCH));
                    }
                }
            }

            if (armATicks == null) {
                var authorization = store.findByReleaseRequestId(RELEASE_REQUEST_ID);
                if (authorization != null) {
                    var context = new ContractNegotiationPolicyContext(
                            new ParticipantAgent("g1-consumer", Map.of(), Map.of()));
                    if (quorumFunction.evaluate(Operator.GEQ, quorum, permission, context)) {
                        armATicks = tick;
                        break;
                    }
                }
            }
        }

        return new RunOutcome(armATicks, null, false);
    }

    private static Permission permissionFor(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }
}
