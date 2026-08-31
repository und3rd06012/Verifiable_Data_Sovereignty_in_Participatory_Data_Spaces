package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

public class ProbeConstraintFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    public static final String MARKER = "BENEFITS_PROBE_EVALUATED";

    private final Monitor monitor;

    public ProbeConstraintFunction(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        monitor.info(MARKER + " operator=" + operator + " rightValue=" + rightValue + " constraints=" + rule.getConstraints().size());
        return true;
    }
}
