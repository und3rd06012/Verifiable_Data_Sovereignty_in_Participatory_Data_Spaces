package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

class PrivacyConstraintFunctionsTest {

    private final ReleaseArtifactStore store = new InMemoryReleaseArtifactStore();

    private ContractNegotiationPolicyContext context() {
        return new ContractNegotiationPolicyContext(
                new ParticipantAgent("consumer", Map.of(), Map.of()));
    }

    private Permission ruleFor(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }

    private void store(String id, int computedK, double epsilon) {
        store.save(new ReleaseArtifact(id, "{}".getBytes(StandardCharsets.UTF_8), computedK, epsilon));
    }

    @Test
    void kAnonymityAllowsWhenComputedKMeetsTheThreshold() {
        store("rr-1", 6, 0.5);
        var fn = new KAnonymityFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-1"), context())).isTrue();
    }

    @Test
    void kAnonymityAllowsAtExactlyTheThreshold() {
        store("rr-2", 5, 0.5);
        var fn = new KAnonymityFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-2"), context())).isTrue();
    }

    @Test
    void kAnonymityRefusesBelowTheThreshold() {
        store("rr-3", 4, 0.5);
        var fn = new KAnonymityFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-3"), context())).isFalse();
    }

    @Test
    void kAnonymityRefusesWhenTheArtifactIsMissing() {
        var ctx = context();
        var fn = new KAnonymityFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-absent"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void kAnonymityRefusesAndReportsAnUnsupportedOperator() {
        store("rr-4", 6, 0.5);
        var ctx = context();
        var fn = new KAnonymityFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.EQ, "5", ruleFor("rr-4"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void kAnonymityRefusesANonNumericRightOperand() {
        store("rr-5", 6, 0.5);
        var ctx = context();
        var fn = new KAnonymityFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.GEQ, "five", ruleFor("rr-5"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void epsilonAllowsWhenChargedIsWithinTheCap() {
        store("rr-6", 6, 0.5);
        var fn = new EpsilonBudgetFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.LEQ, "0.5", ruleFor("rr-6"), context())).isTrue();
    }

    @Test
    void epsilonRefusesWhenChargedExceedsTheCap() {
        store("rr-7", 6, 0.9);
        var fn = new EpsilonBudgetFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.LEQ, "0.5", ruleFor("rr-7"), context())).isFalse();
    }

    @Test
    void epsilonRefusesWhenTheArtifactIsMissing() {
        var ctx = context();
        var fn = new EpsilonBudgetFunction(store, new ConsoleMonitor());
        assertThat(fn.evaluate(Operator.LEQ, "0.5", ruleFor("rr-absent"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void bothRefuseWhenTheReleaseRequestSiblingIsMissing() {
        var bare = Permission.Builder.newInstance().build();
        var kctx = context();
        var ectx = context();
        assertThat(new KAnonymityFunction(store, new ConsoleMonitor()).evaluate(Operator.GEQ, "5", bare, kctx)).isFalse();
        assertThat(new EpsilonBudgetFunction(store, new ConsoleMonitor()).evaluate(Operator.LEQ, "0.5", bare, ectx)).isFalse();
        assertThat(kctx.getProblems()).isNotEmpty();
        assertThat(ectx.getProblems()).isNotEmpty();
    }
}
