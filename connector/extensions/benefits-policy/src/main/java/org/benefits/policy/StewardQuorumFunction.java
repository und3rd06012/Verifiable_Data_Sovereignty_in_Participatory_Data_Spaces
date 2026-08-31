package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

public class StewardQuorumFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    private final StewardAuthorizationStore store;
    private final Monitor monitor;

    public StewardQuorumFunction(StewardAuthorizationStore store, Monitor monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var required = asInteger(rightValue);
        if (required == null) {
            context.reportProblem("stewardQuorum right operand is not an integer: " + rightValue);
            return false;
        }

        var releaseRequestId = Constraints.siblingLiteral(rule, RELEASE_REQUEST_KEY);
        if (releaseRequestId == null) {
            context.reportProblem("stewardQuorum requires a sibling " + RELEASE_REQUEST_KEY + " constraint");
            return false;
        }

        var authorization = store.findByReleaseRequestId(releaseRequestId);
        if (authorization == null) {
            context.reportProblem("no steward authorization for release request " + releaseRequestId);
            return false;
        }

        var signed = authorization.distinctStewardCount();
        monitor.info("steward quorum check releaseRequest=" + releaseRequestId + " signed=" + signed + " required=" + required);

        return switch (operator) {
            case GEQ -> signed >= required;
            case GT -> signed > required;
            case EQ -> signed == required;
            default -> false;
        };
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.valueOf(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
