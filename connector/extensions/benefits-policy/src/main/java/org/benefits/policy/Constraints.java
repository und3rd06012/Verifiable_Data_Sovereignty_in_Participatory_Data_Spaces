package org.benefits.policy;

import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Rule;
import org.jetbrains.annotations.Nullable;

public final class Constraints {

    private Constraints() {
    }

    /**
     * EDC's policy context carries no general purpose data map, so a constraint function
     * that needs a value produced by the policy author reads it from a sibling atomic
     * constraint on the same rule.
     */
    @Nullable
    public static String siblingLiteral(Rule rule, String leftOperand) {
        for (var constraint : rule.getConstraints()) {
            if (!(constraint instanceof AtomicConstraint atomic)) {
                continue;
            }
            if (!(atomic.getLeftExpression() instanceof LiteralExpression left)) {
                continue;
            }
            if (!leftOperand.equals(left.asString())) {
                continue;
            }
            if (atomic.getRightExpression() instanceof LiteralExpression right) {
                return right.asString();
            }
        }
        return null;
    }
}
