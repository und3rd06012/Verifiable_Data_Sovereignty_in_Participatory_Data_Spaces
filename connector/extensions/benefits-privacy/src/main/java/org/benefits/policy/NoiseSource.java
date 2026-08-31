package org.benefits.policy;

/**
 * Supplies calibrated noise. Injectable so tests can seed it and get reproducible
 * output: a differential privacy implementation that cannot be tested
 * deterministically cannot be verified at all.
 */
public interface NoiseSource {

    double noise(double epsilon);
}
