package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

public class RecipientClassFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var recipientClass = context.participantAgent().getClaims().get("recipientClass");
        if (recipientClass == null || recipientClass.toString().isBlank()) {
            context.reportProblem("requester declared no recipientClass claim");
            return false;
        }

        if (operator != Operator.EQ) {
            context.reportProblem("recipientClass does not support operator: " + operator);
            return false;
        }

        return recipientClass.equals(rightValue);
    }
}
