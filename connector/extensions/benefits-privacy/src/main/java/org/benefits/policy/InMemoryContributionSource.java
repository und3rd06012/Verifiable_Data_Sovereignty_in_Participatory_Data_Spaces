package org.benefits.policy;

import java.util.List;

public class InMemoryContributionSource implements ContributionSource {

    private final List<Contribution> contributions;

    public InMemoryContributionSource(List<Contribution> contributions) {
        this.contributions = List.copyOf(contributions);
    }

    @Override
    public List<Contribution> contributionsFor(String siteId) {
        return contributions.stream().filter(c -> c.siteId().equals(siteId)).toList();
    }
}
