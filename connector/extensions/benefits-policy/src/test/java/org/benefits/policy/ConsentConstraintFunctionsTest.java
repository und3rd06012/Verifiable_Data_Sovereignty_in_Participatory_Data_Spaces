package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentConstraintFunctionsTest {

    private ContractNegotiationPolicyContext contextWith(Map<String, Object> claims) {
        return new ContractNegotiationPolicyContext(new ParticipantAgent("consumer", claims, Map.of()));
    }

    private final Permission anyRule = Permission.Builder.newInstance().build();

    @Test
    void purposeAllowsWhenTheRequesterPurposeMatchesTheGrantedValue() {
        var fn = new PurposeFunction();
        var ctx = contextWith(Map.of("purpose", "service-improvement"));
        assertThat(fn.evaluate(Operator.EQ, "service-improvement", anyRule, ctx)).isTrue();
    }

    @Test
    void purposeRefusesAnUngrantedPurpose() {
        var fn = new PurposeFunction();
        var ctx = contextWith(Map.of("purpose", "marketing"));
        assertThat(fn.evaluate(Operator.EQ, "service-improvement", anyRule, ctx)).isFalse();
    }

    @Test
    void purposeRefusesWhenTheClaimIsAbsent() {
        var fn = new PurposeFunction();
        var ctx = contextWith(Map.of());
        assertThat(fn.evaluate(Operator.EQ, "service-improvement", anyRule, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void purposeRefusesAndReportsAnUnsupportedOperator() {
        var fn = new PurposeFunction();
        for (var operator : List.of(Operator.IN, Operator.NEQ, Operator.GEQ)) {
            var ctx = contextWith(Map.of("purpose", "service-improvement"));
            assertThat(fn.evaluate(operator, "service-improvement", anyRule, ctx)).isFalse();
            assertThat(ctx.getProblems()).isNotEmpty();
        }
    }

    @Test
    void recipientClassMatchesExactly() {
        var fn = new RecipientClassFunction();
        assertThat(fn.evaluate(Operator.EQ, "statistics", anyRule,
                contextWith(Map.of("recipientClass", "statistics")))).isTrue();
        assertThat(fn.evaluate(Operator.EQ, "statistics", anyRule,
                contextWith(Map.of("recipientClass", "provider")))).isFalse();
    }

    @Test
    void recipientClassRefusesWhenTheClaimIsAbsent() {
        var fn = new RecipientClassFunction();
        var ctx = contextWith(Map.of());
        assertThat(fn.evaluate(Operator.EQ, "statistics", anyRule, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void recipientClassRefusesAndReportsAnUnsupportedOperator() {
        var fn = new RecipientClassFunction();
        for (var operator : List.of(Operator.IN, Operator.NEQ, Operator.GEQ)) {
            var ctx = contextWith(Map.of("recipientClass", "statistics"));
            assertThat(fn.evaluate(operator, "statistics", anyRule, ctx)).isFalse();
            assertThat(ctx.getProblems()).isNotEmpty();
        }
    }

    @Test
    void fieldSetRefusesARequestedFieldOutsideTheGrantedSet() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "service.satisfaction,health.condition"));
        assertThat(fn.evaluate(Operator.IN, List.of("service.satisfaction"), anyRule, ctx)).isFalse();
    }

    @Test
    void fieldSetAllowsASubsetOfTheGrantedSet() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "service.satisfaction"));
        assertThat(fn.evaluate(Operator.IN, List.of("service.satisfaction", "service.waitTime"), anyRule, ctx)).isTrue();
    }

    // JSON-LD expansion collapses a single-element array into a bare scalar, so the
    // common case of granting exactly one field arrives as a String, not a List. This is
    // deliberate accommodation of that expansion behaviour, not a redundant fallback:
    // without it, every single-field grant would be misread as a type mismatch and the
    // negotiation would fail as if consent had been refused.
    @Test
    void fieldSetAllowsAScalarGrantedOperandMatchingTheRequestedField() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "service.satisfaction"));
        assertThat(fn.evaluate(Operator.IN, "service.satisfaction", anyRule, ctx)).isTrue();
    }

    @Test
    void fieldSetRefusesAScalarGrantedOperandNotCoveringTheRequestedField() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "health.condition"));
        assertThat(fn.evaluate(Operator.IN, "service.satisfaction", anyRule, ctx)).isFalse();
    }

    @Test
    void fieldSetRefusesAndReportsAMalformedGrantedOperand() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "service.satisfaction"));
        assertThat(fn.evaluate(Operator.IN, 42, anyRule, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void fieldSetRefusesWhenTheClaimIsAbsent() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of());
        assertThat(fn.evaluate(Operator.IN, List.of("service.satisfaction"), anyRule, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void fieldSetRefusesAndReportsAnUnsupportedOperator() {
        var fn = new FieldSetFunction();
        for (var operator : List.of(Operator.EQ, Operator.NEQ, Operator.GEQ)) {
            var ctx = contextWith(Map.of("requestedFields", "service.satisfaction"));
            assertThat(fn.evaluate(operator, List.of("service.satisfaction"), anyRule, ctx)).isFalse();
            assertThat(ctx.getProblems()).isNotEmpty();
        }
    }
}
