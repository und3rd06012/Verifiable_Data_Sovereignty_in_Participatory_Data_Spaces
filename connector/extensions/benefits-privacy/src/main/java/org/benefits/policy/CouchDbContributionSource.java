package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads contributions from CouchDB using a Mango query.
 * <p>
 * Only the site filter is pushed to the database. Grouping, suppression and noise all
 * happen in Java, so the privacy logic stays testable without a database.
 */
public class CouchDbContributionSource implements ContributionSource {

    private static final Logger LOGGER = Logger.getLogger(CouchDbContributionSource.class.getName());

    private static final List<String> QUASI_IDENTIFIERS = List.of("channel");
    private static final int MAX_RESULTS = 100_000;

    private final String baseUrl;
    private final String database;
    private final String authorization;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbContributionSource(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
    }

    @Override
    public List<Contribution> contributionsFor(String siteId) {
        try {
            var selector = buildSelector(siteId);

            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_find"))
                    .header("authorization", authorization)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(selector))
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB query failed with status " + response.statusCode());
            }
            var root = mapper.readTree(response.body());
            var docs = root.path("docs");
            if (docs.size() == MAX_RESULTS) {
                throw new EdcException("CouchDB query for siteId " + siteId
                        + " may have been truncated at the limit of " + MAX_RESULTS
                        + " results; pagination is not implemented");
            }
            return parse(root);
        } catch (IOException e) {
            throw new EdcException("CouchDB query failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB query interrupted", e);
        }
    }

    private String buildSelector(String siteId) throws IOException {
        var selector = mapper.createObjectNode();
        var criteria = selector.putObject("selector");
        criteria.put("type", "contribution");
        criteria.put("siteId", siteId);
        selector.put("limit", MAX_RESULTS);
        return mapper.writeValueAsString(selector);
    }

    private List<Contribution> parse(JsonNode root) {
        List<Contribution> contributions = new ArrayList<>();
        for (JsonNode doc : root.path("docs")) {
            if (!doc.hasNonNull("participantRef") || doc.get("participantRef").asText().isBlank()) {
                var docId = doc.hasNonNull("_id") ? doc.get("_id").asText() : "none";
                LOGGER.warning("skipping contribution document with missing participantRef, _id=" + docId);
                continue;
            }
            var participantRef = doc.get("participantRef").asText();

            Map<String, String> attributes = new HashMap<>();
            var nonScalar = false;
            for (var name : QUASI_IDENTIFIERS) {
                if (doc.hasNonNull(name)) {
                    var value = doc.get(name);
                    if (value.isObject() || value.isArray()) {
                        var docId = doc.hasNonNull("_id") ? doc.get("_id").asText() : "none";
                        LOGGER.warning("skipping contribution document with non-scalar " + name
                                + ", _id=" + docId);
                        nonScalar = true;
                        break;
                    }
                    attributes.put(name, value.asText());
                }
            }
            if (nonScalar) {
                continue;
            }
            for (Iterator<String> it = doc.path("fields").fieldNames(); it.hasNext(); ) {
                var field = it.next();
                attributes.put("field." + field, doc.path("fields").path(field).asText());
            }
            contributions.add(new Contribution(
                    participantRef,
                    doc.path("siteId").asText(),
                    attributes));
        }
        return contributions;
    }
}
