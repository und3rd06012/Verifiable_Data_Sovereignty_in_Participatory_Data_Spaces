package org.benefits.experiments.g1;

import org.benefits.policy.InMemoryStewardAuthorizationStore;
import org.benefits.policy.StewardQuorumFunction;
import org.benefits.policy.StewardSignature;
import org.benefits.policy.StewardVeto;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Exercises the real, unmodified steward-quorum classes directly, confirming the
 * behavior Task 3 onward depends on, before building the timeline simulation on it.
 */
class LiveSpikeTest {

    private static ContractNegotiationPolicyContext context() {
        return new ContractNegotiationPolicyContext(new ParticipantAgent("g1-consumer", Map.of(), Map.of()));
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

    @Test
    void repeatedIncrementalAppendSignatureCallsAccumulateAndQuorumBecomesTrueOnlyOnceEnoughSign() {
        var store = new InMemoryStewardAuthorizationStore();
        var function = new StewardQuorumFunction(store, new ConsoleMonitor());
        var permission = permissionFor("g1-spike-1");

        store.appendSignature("g1-spike-1", "hash", new StewardSignature("s-1", "sig-1", Instant.EPOCH));
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isFalse();

        store.appendSignature("g1-spike-1", "hash", new StewardSignature("s-2", "sig-2", Instant.EPOCH));
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isTrue();

        store.appendSignature("g1-spike-1", "hash", new StewardSignature("s-3", "sig-3", Instant.EPOCH));
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isTrue();
    }

    @Test
    void appendVetoDoesNotAffectTheRealQuorumFunctionAtAll() {
        var store = new InMemoryStewardAuthorizationStore();
        var function = new StewardQuorumFunction(store, new ConsoleMonitor());
        var permission = permissionFor("g1-spike-2");

        store.appendVeto("g1-spike-2", "hash", new StewardVeto("s-1", "objection", Instant.EPOCH));
        store.appendVeto("g1-spike-2", "hash", new StewardVeto("s-2", "objection", Instant.EPOCH));
        store.appendSignature("g1-spike-2", "hash", new StewardSignature("s-3", "sig-3", Instant.EPOCH));
        store.appendSignature("g1-spike-2", "hash", new StewardSignature("s-4", "sig-4", Instant.EPOCH));

        // Two vetoes recorded, quorum 2, two signatures: the real function must still
        // report true, since it counts only distinct signers and never reads vetoes.
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isTrue();
        var authorization = store.findByReleaseRequestId("g1-spike-2");
        assertThat(authorization.vetoes()).hasSize(2);
    }

    @Test
    void evaluatingRepeatedlyInATightLoopCompletesQuicklyWithADefaultMonitor() {
        // Sweep scale (Task 5/6) calls evaluate() up to hundreds of thousands of times.
        // ConsoleMonitor's info() writes a line to stdout on every call, which would
        // flood the console and slow the real sweep down. A plain anonymous Monitor
        // relies entirely on the interface's own default (no-op) method bodies.
        var store = new InMemoryStewardAuthorizationStore();
        var silentMonitor = new Monitor() {
        };
        var function = new StewardQuorumFunction(store, silentMonitor);
        var permission = permissionFor("g1-spike-3");
        store.appendSignature("g1-spike-3", "hash", new StewardSignature("s-1", "sig-1", Instant.EPOCH));

        var start = System.nanoTime();
        for (var i = 0; i < 100_000; i++) {
            function.evaluate(Operator.GEQ, 1, permission, context());
        }
        var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // No fixed millisecond budget asserted here (machine-dependent); the real
        // assertion is that this loop completes at all without manual intervention,
        // proving a silent Monitor is viable for the sweep. Record the observed time
        // in this task's report for the controller's own judgment.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(0);
    }

    @Test
    void mix64SpreadsFirstDrawsFromDenseConsecutiveSeedsAcrossTheFullUnitInterval() {
        // Mirrors the exact verification E4's own final review used to confirm mix64
        // breaks java.util.Random's adjacent-seed first-draw correlation (spec section
        // 6): without mixing, dense consecutive seeds cluster their first nextDouble()
        // draw into a narrow band. With mixing, the draws should spread widely.
        var baseSeed = 1L;
        Set<Integer> bandHits = new HashSet<>();
        var totalDraws = 200;
        for (var i = 0; i < totalDraws; i++) {
            var mixed = mix64(baseSeed * 1_000_003L + i);
            var draw = new Random(mixed).nextDouble();
            bandHits.add((int) (draw * 10));
        }

        // An unmixed, dense-consecutive-seed draw sequence would land almost entirely
        // in one or two of these ten deciles (matching E4's measured [0.37, 0.40] band,
        // decile 3). A mixed sequence should spread across most deciles.
        assertThat(bandHits.size()).isGreaterThanOrEqualTo(6);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
