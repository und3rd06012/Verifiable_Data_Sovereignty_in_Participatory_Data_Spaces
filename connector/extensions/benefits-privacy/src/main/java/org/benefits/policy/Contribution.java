package org.benefits.policy;

import java.util.List;
import java.util.Map;

/**
 * One contributed record as the privacy pipeline sees it. The attributes map holds
 * the quasi-identifier values a query may group by, such as channel or service type.
 */
public record Contribution(String participantRef, String siteId, Map<String, String> attributes,
                            List<ConsentGrant> consentGrants) {

    /**
     * Sentinel returned by {@link #attribute(String)} for an attribute the map has no
     * entry for. It must never equal a value real data can carry, so that an absent
     * attribute and an explicit empty string never collapse into the same group key.
     * A leading space is not a value any of this project's JSON producers can emit for
     * a quasi-identifier.
     */
    public static final String ABSENT = " ABSENT";

    public Contribution {
        attributes = Map.copyOf(attributes);
        consentGrants = consentGrants == null ? null : List.copyOf(consentGrants);
    }

    public Contribution(String participantRef, String siteId, Map<String, String> attributes) {
        this(participantRef, siteId, attributes, null);
    }

    public String attribute(String name) {
        return attributes.getOrDefault(name, ABSENT);
    }
}
