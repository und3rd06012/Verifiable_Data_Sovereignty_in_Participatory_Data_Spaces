package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

class StewardQuorumFunctionTest {

    private final StewardAuthorizationStore store = new InMemoryStewardAuthorizationStore();
    private StewardQuorumFunction function;

    @BeforeEach
    void setUp() {
        function = new StewardQuorumFunction(store, new ConsoleMonitor());
    }

    private ContractNegotiationPolicyContext context() {
        return new ContractNegotiationPolicyContext(new ParticipantAgent("consumer", Map.of(), Map.of()));
    }

    private Permission permissionWithRelease(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }

    private void saveAuthorization(String releaseRequestId, String... stewardIds) {
        var signatures = java.util.Arrays.stream(stewardIds)
                .map(id -> new StewardSignature(id, "sig-" + id, Instant.parse("2026-08-30T12:00:00Z")))
                .toList();
        store.save(new StewardAuthorization(releaseRequestId, "hash", List.copyOf(signatures),
                Instant.parse("2026-08-30T12:00:00Z")));
    }

    @Test
    void allowsWhenQuorumIsMet() {
        saveAuthorization("rr-1", "s-1", "s-2");
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-1"), context())).isTrue();
    }

    @Test
    void refusesWhenOnlyOneStewardSigned() {
        saveAuthorization("rr-2", "s-1");
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-2"), context())).isFalse();
    }

    @Test
    void refusesWhenOneStewardSignedTwice() {
        saveAuthorization("rr-3", "s-1", "s-1");
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-3"), context())).isFalse();
    }

    @Test
    void refusesWhenNoAuthorizationExists() {
        var ctx = context();
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-absent"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void refusesWhenTheReleaseRequestSiblingIsMissing() {
        var ctx = context();
        var bare = Permission.Builder.newInstance().build();
        assertThat(function.evaluate(Operator.GEQ, 2, bare, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void refusesWhenTheRightOperandIsNotAnInteger() {
        saveAuthorization("rr-4", "s-1", "s-2");
        var ctx = context();
        assertThat(function.evaluate(Operator.GEQ, "two", permissionWithRelease("rr-4"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void refusesAnUnsupportedOperator() {
        saveAuthorization("rr-5", "s-1", "s-2");
        assertThat(function.evaluate(Operator.NEQ, 2, permissionWithRelease("rr-5"), context())).isFalse();
    }
}
