package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class FieldSetFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var claim = context.participantAgent().getClaims().get("requestedFields");
        if (claim == null || claim.toString().isBlank()) {
            context.reportProblem("requester declared no requestedFields claim");
            return false;
        }
        List<String> requested = Arrays.stream(claim.toString().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (operator != Operator.IN) {
            context.reportProblem("fieldSet does not support operator: " + operator);
            return false;
        }

        // JSON-LD expansion collapses a single-element array into a bare scalar, so a
        // one-field grant (the common case) arrives here as a String, not a Collection.
        // Accepting the scalar and normalising it to a one-element set is deliberate
        // accommodation of that expansion behaviour, not an oversight: without it, every
        // single-field grant would be rejected as a type mismatch and misread as a
        // consent refusal. Elements of a genuine collection are also normalised with
        // String.valueOf, since expansion does not guarantee they are String instances
        // either.
        List<String> granted;
        if (rightValue instanceof Collection<?> c) {
            granted = c.stream().map(String::valueOf).toList();
        } else if (rightValue instanceof String s) {
            granted = List.of(s);
        } else {
            context.reportProblem("fieldSet right operand is neither a collection nor a string: " + rightValue);
            return false;
        }

        return granted.containsAll(requested);
    }
}
