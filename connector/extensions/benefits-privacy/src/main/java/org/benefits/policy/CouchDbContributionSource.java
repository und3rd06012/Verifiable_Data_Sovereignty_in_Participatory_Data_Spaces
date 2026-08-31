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

/**
 * Reads contributions from CouchDB using a Mango query.
 * <p>
 * Only the site filter is pushed to the database. Grouping, suppression and noise all
 * happen in Java, so the privacy logic stays testable without a database.
 */
public class CouchDbContributionSource implements ContributionSource {

    private static final List<String> QUASI_IDENTIFIERS = List.of("channel", "service");

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
        var selector = """
                {"selector":{"type":"contribution","siteId":"%s"},"limit":100000}"""
                .formatted(siteId);

        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_find"))
                .header("authorization", authorization)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(selector))
                .build();

        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB query failed with status " + response.statusCode());
            }
            return parse(mapper.readTree(response.body()));
        } catch (IOException e) {
            throw new EdcException("CouchDB query failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB query interrupted", e);
        }
    }

    private List<Contribution> parse(JsonNode root) {
        List<Contribution> contributions = new ArrayList<>();
        for (JsonNode doc : root.path("docs")) {
            Map<String, String> attributes = new HashMap<>();
            for (var name : QUASI_IDENTIFIERS) {
                if (doc.hasNonNull(name)) {
                    attributes.put(name, doc.get(name).asText());
                }
            }
            for (Iterator<String> it = doc.path("fields").fieldNames(); it.hasNext(); ) {
                var field = it.next();
                attributes.put("field." + field, doc.path("fields").path(field).asText());
            }
            contributions.add(new Contribution(
                    doc.path("participantRef").asText(),
                    doc.path("siteId").asText(),
                    attributes));
        }
        return contributions;
    }
}
