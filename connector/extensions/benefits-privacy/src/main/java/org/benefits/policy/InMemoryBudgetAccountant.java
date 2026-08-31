package org.benefits.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory accountant. A restart resets every site's budget, which is a stated
 * limitation of this reference implementation and materially more consequential
 * than the equivalent limitation on the steward authorization store: a reset
 * silently restores spending capacity that was legitimately exhausted.
 */
public class InMemoryBudgetAccountant implements BudgetAccountant {

    private final double total;
    private final Map<String, AtomicReference<Double>> remaining = new ConcurrentHashMap<>();

    public InMemoryBudgetAccountant(double total) {
        this.total = total;
    }

    @Override
    public boolean reserve(String siteId, double epsilon) {
        if (epsilon <= 0) {
            return false;
        }
        var cell = cellFor(siteId);
        while (true) {
            var current = cell.get();
            if (current < epsilon) {
                return false;
            }
            var next = current - epsilon;
            if (cell.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    @Override
    public double remaining(String siteId) {
        return cellFor(siteId).get();
    }

    private AtomicReference<Double> cellFor(String siteId) {
        return remaining.computeIfAbsent(siteId, unused -> new AtomicReference<>(total));
    }
}
