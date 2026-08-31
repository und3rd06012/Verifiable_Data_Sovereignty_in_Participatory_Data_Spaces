package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Refuses a release whose stored artifact was charged more epsilon than the policy
 * permits.
 */
public class EpsilonBudgetFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    private final ReleaseArtifactStore store;
    private final Monitor monitor;

    public EpsilonBudgetFunction(ReleaseArtifactStore store, Monitor monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        if (operator != Operator.LEQ) {
            context.reportProblem("epsilonBudget does not support operator: " + operator);
            return false;
        }

        Double cap = asDouble(rightValue);
        if (cap == null) {
            context.reportProblem("epsilonBudget right operand is not a number: " + rightValue);
            return false;
        }

        var releaseRequestId = Constraints.siblingLiteral(rule, RELEASE_REQUEST_KEY);
        if (releaseRequestId == null) {
            context.reportProblem("epsilonBudget requires a sibling " + RELEASE_REQUEST_KEY + " constraint");
            return false;
        }

        var artifact = store.findByReleaseRequestId(releaseRequestId);
        if (artifact == null) {
            context.reportProblem("no release artifact for release request " + releaseRequestId);
            return false;
        }

        monitor.info("epsilonBudget check releaseRequest=" + releaseRequestId + " charged=" + artifact.epsilonCharged() + " cap=" + cap);

        return artifact.epsilonCharged() <= cap;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.valueOf(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
