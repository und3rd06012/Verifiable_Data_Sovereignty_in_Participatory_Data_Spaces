package org.benefits.policy;

import java.util.List;

/**
 * Supplies the raw contributions a query aggregates over.
 * <p>
 * This interface exists so that every k-anonymity and differential privacy test runs
 * without a database. Only the CouchDB-backed implementation needs an integration test,
 * and it verifies the reader rather than the mathematics.
 */
public interface ContributionSource {

    List<Contribution> contributionsFor(String siteId);
}
