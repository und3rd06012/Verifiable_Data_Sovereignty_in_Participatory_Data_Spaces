package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

public class PurposeFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var purpose = context.participantAgent().getClaims().get("purpose");
        if (purpose == null || purpose.toString().isBlank()) {
            context.reportProblem("requester declared no purpose claim");
            return false;
        }

        if (operator != Operator.EQ) {
            context.reportProblem("purpose does not support operator: " + operator);
            return false;
        }

        return purpose.equals(rightValue);
    }
}
