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
        if (!(rightValue instanceof Collection<?> granted)) {
            context.reportProblem("fieldSet right operand is not a collection: " + rightValue);
            return false;
        }
        return granted.containsAll(requested);
    }
}
