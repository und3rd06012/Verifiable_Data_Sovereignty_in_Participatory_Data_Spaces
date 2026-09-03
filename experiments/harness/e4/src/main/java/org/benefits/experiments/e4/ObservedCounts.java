package org.benefits.experiments.e4;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.benefits.policy.ReleaseArtifact;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a PrivacyPipeline.compute() result's JSON bytes (the {"groups":[{"group":...,
 * "count":...}]} shape PrivacyPipeline itself produces) into a group-key-to-noised-count
 * map. An empty Optional (a refused query) parses to an empty map, matching "this query
 * produced no data" uniformly regardless of whether the whole query was refused or a
 * specific group was suppressed.
 */
public final class ObservedCounts {

    private ObservedCounts() {
    }

    public static Map<String, Double> parse(Optional<ReleaseArtifact> released) {
        if (released.isEmpty()) {
            return Map.of();
        }
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(released.get().bytes());
            Map<String, Double> result = new HashMap<>();
            for (var groupNode : root.get("groups")) {
                result.put(groupNode.get("group").asText(), groupNode.get("count").asDouble());
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse release artifact JSON", e);
        }
    }
}
