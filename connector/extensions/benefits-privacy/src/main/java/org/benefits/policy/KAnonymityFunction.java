package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Refuses a release whose stored artifact does not meet the required k.
 * <p>
 * The artifact is computed and stored when the release request is created, long
 * before this runs, because constraint functions evaluate during contract
 * negotiation and no transfer exists yet.
 */
public class KAnonymityFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    private final ReleaseArtifactStore store;
    private final Monitor monitor;

    public KAnonymityFunction(ReleaseArtifactStore store, Monitor monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        if (operator != Operator.GEQ) {
            context.reportProblem("kAnonymity does not support operator: " + operator);
            return false;
        }

        Integer required = asInteger(rightValue);
        if (required == null) {
            context.reportProblem("kAnonymity right operand is not an integer: " + rightValue);
            return false;
        }

        var releaseRequestId = Constraints.siblingLiteral(rule, RELEASE_REQUEST_KEY);
        if (releaseRequestId == null) {
            context.reportProblem("kAnonymity requires a sibling " + RELEASE_REQUEST_KEY + " constraint");
            return false;
        }

        var artifact = store.findByReleaseRequestId(releaseRequestId);
        if (artifact == null) {
            context.reportProblem("no release artifact for release request " + releaseRequestId);
            return false;
        }

        monitor.info("kAnonymity check releaseRequest=" + releaseRequestId + " computedK=" + artifact.computedK() + " required=" + required);

        return artifact.computedK() >= required;
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
