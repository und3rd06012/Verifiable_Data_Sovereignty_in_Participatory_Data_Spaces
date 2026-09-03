package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a synthetic community population deterministically from a seed, population
 * size, and skew mode (spec section 3). A fresh java.util.Random is constructed per call
 * from a derived seed, not reused as a field, so the result depends only on (seed,
 * populationSize, skewed), never on call order.
 */
public class SeededPopulationGenerator {

    public static final String SITE_ID = "e4-sweep-site";

    private static final String[] SERVICE_TYPES =
            {"housing", "food-assistance", "healthcare", "legal-aid", "employment"};
    private static final String[] CHANNELS = {"app", "sms", "paper", "voice"};
    private static final String[] URGENCY_LEVELS = {"routine", "urgent", "emergency"};

    // Cumulative probability boundaries, one per category in the arrays above, summing
    // to 1.0. housing 50%, remaining four service types splitting the other 50% evenly.
    private static final double[] SERVICE_TYPE_SKEW = {0.50, 0.625, 0.75, 0.875, 1.0};
    // paper 61% (the workshop statistic), remaining 39% split evenly across app/sms/voice.
    private static final double[] CHANNEL_SKEW = {0.13, 0.26, 0.87, 1.0};
    // routine 60%, remaining 40% split evenly between urgent and emergency.
    private static final double[] URGENCY_SKEW = {0.60, 0.80, 1.0};

    private final long seed;

    public SeededPopulationGenerator(long seed) {
        this.seed = seed;
    }

    public List<Contribution> generate(int populationSize, boolean skewed) {
        var random = new Random(derivedSeed(populationSize, skewed));
        List<Contribution> contributions = new ArrayList<>();
        for (var i = 0; i < populationSize; i++) {
            Map<String, String> attributes = new HashMap<>();
            attributes.put("serviceType", pick(random, SERVICE_TYPES, skewed ? SERVICE_TYPE_SKEW : null));
            attributes.put("channel", pick(random, CHANNELS, skewed ? CHANNEL_SKEW : null));
            attributes.put("urgencyLevel", pick(random, URGENCY_LEVELS, skewed ? URGENCY_SKEW : null));
            contributions.add(new Contribution("synthetic-participant-" + seed + "-" + i, SITE_ID, attributes));
        }
        return List.copyOf(contributions);
    }

    private static String pick(Random random, String[] categories, double[] cumulative) {
        var draw = random.nextDouble();
        if (cumulative == null) {
            var index = (int) (draw * categories.length);
            return categories[Math.min(index, categories.length - 1)];
        }
        for (var i = 0; i < cumulative.length; i++) {
            if (draw < cumulative[i]) {
                return categories[i];
            }
        }
        return categories[categories.length - 1];
    }

    private long derivedSeed(int populationSize, boolean skewed) {
        return mix64(seed * 1_000_003L + populationSize * 2L + (skewed ? 1 : 0));
    }

    // Standard MurmurHash3 fmix64 finalizer, breaking java.util.Random's adjacent-seed
    // first-draw correlation for the long runs of consecutive derived-seed values this
    // sweep produces (spec section 6).
    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
