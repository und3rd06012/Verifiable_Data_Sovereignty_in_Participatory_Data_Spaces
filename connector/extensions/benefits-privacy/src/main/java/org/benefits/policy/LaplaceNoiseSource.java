package org.benefits.policy;

import java.util.Random;

/**
 * Laplace noise by inverse transform sampling, at scale 1/epsilon.
 * <p>
 * Scale is 1/epsilon rather than sensitivity/epsilon because this pipeline only
 * supports grouped counts over deduplicated participants, where the L1 sensitivity
 * is exactly 1. That condition is enforced in KAnonymity, not assumed here.
 * <p>
 * Floating point side channels in this mechanism are out of scope for this
 * reference implementation, per the design spec.
 */
public class LaplaceNoiseSource implements NoiseSource {

    private final Random random;

    public LaplaceNoiseSource(Random random) {
        this.random = random;
    }

    @Override
    public double noise(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("epsilon must be positive, got: " + epsilon);
        }
        var scale = 1.0 / epsilon;
        var u = random.nextDouble() - 0.5;
        return -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }
}
