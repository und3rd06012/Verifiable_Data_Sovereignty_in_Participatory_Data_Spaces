package org.benefits.policy;

import java.util.Map;

/**
 * One contributed record as the privacy pipeline sees it. The attributes map holds
 * the quasi-identifier values a query may group by, such as channel or service type.
 */
public record Contribution(String participantRef, String siteId, Map<String, String> attributes) {

    public Contribution {
        attributes = Map.copyOf(attributes);
    }

    public String attribute(String name) {
        return attributes.getOrDefault(name, "");
    }
}
