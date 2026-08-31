package org.benefits.policy;

/**
 * Tracks the differential privacy budget for each site.
 * <p>
 * There is deliberately no read-then-write API. {@link #reserve} is the only
 * mutating operation and it is atomic, because two callers each reading the same
 * remaining budget and each deciding they can afford it would overspend, releasing
 * under-noised figures with nothing in the system aware of it. That is a privacy
 * guarantee violated rather than data lost, so it cannot be handled by a documented
 * sole-writer precondition the way the steward authorization store is.
 */
public interface BudgetAccountant {

    /**
     * Atomically reserves epsilon for a site. Returns false, changing nothing, when
     * the remaining budget is insufficient or epsilon is not positive.
     */
    boolean reserve(String siteId, double epsilon);

    double remaining(String siteId);
}
