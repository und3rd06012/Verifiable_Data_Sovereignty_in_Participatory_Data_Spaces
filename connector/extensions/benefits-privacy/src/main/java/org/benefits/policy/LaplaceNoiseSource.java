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
 * <p>
 * Reproducibility from a seed requires either single-threaded use or one instance
 * per thread: a single instance shared across concurrent callers makes draw order
 * scheduling-dependent, which would silently break the seeded determinism this
 * class exists to provide.
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
        // nextDouble() can legitimately return exactly 0.0, which would make
        // 1 - 2|u| zero and the logarithm infinite, so that one point is
        // resampled. Resampling a single point of a continuous distribution
        // does not change it.
        double u;
        do {
            u = random.nextDouble() - 0.5;
        } while (u == -0.5);
        return -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }
}
