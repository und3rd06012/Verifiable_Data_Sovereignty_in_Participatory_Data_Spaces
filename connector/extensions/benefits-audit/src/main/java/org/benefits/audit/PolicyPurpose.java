package org.benefits.audit;

import org.benefits.policy.BenefitsNamespace;
import org.benefits.policy.Constraints;
import org.eclipse.edc.policy.model.Policy;

/**
 * Reads the scalar benefits:purpose constraint phase 2's compiler emits (ruling R25
 * fixed this to a scalar eq, never a set, so there is exactly one value to read).
 */
final class PolicyPurpose {

    private PolicyPurpose() {
    }

    static String extract(Policy policy) {
        if (policy == null || policy.getPermissions().isEmpty()) {
            return "unknown";
        }
        var purpose = Constraints.siblingLiteral(policy.getPermissions().get(0), BenefitsNamespace.PURPOSE_KEY);
        return purpose == null ? "unknown" : purpose;
    }
}
