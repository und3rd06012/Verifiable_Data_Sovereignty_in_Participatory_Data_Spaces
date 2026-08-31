package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A Java transcription of the record-level consent evaluation rules that
 * live in {@code packages/consent-core/src/evaluate.ts} (see
 * {@code conformance/README.md}). It exists to prove those rules port
 * faithfully to Java; it is exercised only by {@code ConformanceVectorTest}
 * and is NOT invoked by the connector at runtime. Live release-boundary
 * enforcement (S2) is ODRL constraint evaluation in
 * {@code PurposeFunction}, {@code RecipientClassFunction},
 * {@code FieldSetFunction} and {@code StewardQuorumFunction}, which
 * evaluate a policy against a requester's claims, not a
 * {@code ContributionRecord}.
 */
public final class ConsentEvaluator {

    private ConsentEvaluator() {
    }

    public static String evaluate(JsonNode record, String nowIso) {
        if (record == null || !record.hasNonNull("type") || !"contribution".equals(record.get("type").asText())) {
            return "not-a-contribution";
        }

        JsonNode consent = record.get("consent");
        if (consent == null || consent.isNull() || !consent.has("grants") || consent.get("grants").isNull()) {
            return "missing-consent-assertion";
        }

        if (!consent.path("participantRef").asText().equals(record.path("participantRef").asText())) {
            return "participant-mismatch";
        }

        JsonNode fields = record.get("fields");
        if (fields != null && !fields.isNull() && !fields.isObject()) {
            return "malformed-fields";
        }

        List<String> names = new ArrayList<>();
        if (fields != null && fields.isObject()) {
            for (Iterator<String> it = fields.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
        }
        names.sort(String::compareTo);

        String purpose = record.path("declaredPurpose").asText();
        String recipientClass = record.path("declaredRecipientClass").asText();

        for (String name : names) {
            if (!hasGrant(consent.get("grants"), name, purpose, recipientClass, nowIso)) {
                return "no-grant:" + name;
            }
        }

        return "ok";
    }

    private static boolean hasGrant(JsonNode grants, String field, String purpose, String recipientClass, String nowIso) {
        if (grants == null || !grants.isArray()) {
            return false;
        }
        for (JsonNode g : grants) {
            if (!field.equals(g.path("field").asText())) {
                continue;
            }
            if (!purpose.equals(g.path("purpose").asText())) {
                continue;
            }
            if (!recipientClass.equals(g.path("recipientClass").asText())) {
                continue;
            }
            if (g.path("grantedAt").asText().compareTo(nowIso) > 0) {
                continue;
            }
            JsonNode expiresAt = g.get("expiresAt");
            if (expiresAt != null && !expiresAt.isNull() && expiresAt.asText().compareTo(nowIso) <= 0) {
                continue;
            }
            return true;
        }
        return false;
    }
}
