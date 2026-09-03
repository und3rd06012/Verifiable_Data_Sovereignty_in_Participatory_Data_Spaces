package org.benefits.experiments.e4;

import org.benefits.policy.QuerySpec;

import java.util.List;

/**
 * The eight fixed community queries (spec section 4), in the exact order they run
 * against one site's shared budget (spec section 6). Order matters: budget exhaustion
 * depends on it.
 */
public final class E4Queries {

    private E4Queries() {
    }

    public enum Kind { GROUPED, PROPORTION_LARGEST, PROPORTION_TARGET }

    public record E4Query(String name, QuerySpec spec, Kind kind, String targetCategory) {
    }

    public static final String RARE_SERVICE_TYPE = "legal-aid";

    private static final QuerySpec SERVICE_TYPE =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType"));
    private static final QuerySpec CHANNEL =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("channel"));
    private static final QuerySpec URGENCY =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("urgencyLevel"));
    private static final QuerySpec SERVICE_TYPE_BY_CHANNEL =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType", "channel"));
    private static final QuerySpec SERVICE_TYPE_BY_URGENCY =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType", "urgencyLevel"));
    private static final QuerySpec SERVICE_TYPE_BY_CHANNEL_BY_URGENCY =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType", "channel", "urgencyLevel"));

    public static List<E4Query> workload() {
        return List.of(
                new E4Query("count-service-type", SERVICE_TYPE, Kind.GROUPED, null),
                new E4Query("count-channel", CHANNEL, Kind.GROUPED, null),
                new E4Query("count-urgency", URGENCY, Kind.GROUPED, null),
                new E4Query("proportion-largest-service-type", SERVICE_TYPE, Kind.PROPORTION_LARGEST, null),
                new E4Query("proportion-legal-aid", SERVICE_TYPE, Kind.PROPORTION_TARGET, RARE_SERVICE_TYPE),
                new E4Query("crosstab-service-type-channel", SERVICE_TYPE_BY_CHANNEL, Kind.GROUPED, null),
                new E4Query("crosstab-service-type-urgency", SERVICE_TYPE_BY_URGENCY, Kind.GROUPED, null),
                new E4Query("crosstab-service-type-channel-urgency", SERVICE_TYPE_BY_CHANNEL_BY_URGENCY,
                        Kind.GROUPED, null)
        );
    }
}
