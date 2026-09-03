package org.benefits.experiments.g2;

import org.benefits.experiments.e4.SeededPopulationGenerator;
import org.benefits.policy.Contribution;
import org.benefits.policy.ConsentGrant;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reuses SeededPopulationGenerator (e4, unmodified) for a population's serviceType/
 * channel/urgencyLevel attributes, then layers a consent grant set onto each
 * resulting Contribution according to a heterogeneity parameter h (spec section 4.2).
 */
public class HeterogeneousPopulationGenerator {

    public static final String PURPOSE = "community-insight";
    public static final String RECIPIENT_CLASS = "community";
    private static final String[] FIELDS = {"serviceType", "channel", "urgencyLevel"};

    private final long seed;

    public HeterogeneousPopulationGenerator(long seed) {
        this.seed = seed;
    }

    public List<Contribution> generate(int populationSize, double h) {
        var base = new SeededPopulationGenerator(seed).generate(populationSize, false);
        List<Contribution> withConsent = new ArrayList<>();

        for (var i = 0; i < base.size(); i++) {
            var contribution = base.get(i);
            var random = new Random(mix64(seed * 1_000_003L + i * 7L));
            var narrow = random.nextDouble() < h;
            String omittedField = null;
            if (narrow) {
                omittedField = FIELDS[random.nextInt(FIELDS.length)];
            }

            List<ConsentGrant> grants = new ArrayList<>();
            for (var field : FIELDS) {
                if (field.equals(omittedField)) {
                    continue;
                }
                grants.add(new ConsentGrant(field, PURPOSE, RECIPIENT_CLASS,
                        "2020-01-01T00:00:00Z", null, null));
            }

            withConsent.add(new Contribution(contribution.participantRef(), contribution.siteId(),
                    contribution.attributes(), grants));
        }

        return List.copyOf(withConsent);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
