package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class CouchDbReleaseRequestStore implements ReleaseRequestStore {

    private static final int MAX_RESULTS = 100_000;

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbReleaseRequestStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        ensureDatabaseExists();
    }

    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authHeader).PUT(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 412) {
                throw new EdcException("failed to create CouchDB database " + database
                        + ", status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to create CouchDB database " + database, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating CouchDB database " + database, e);
        }
    }

    @Override
    @Nullable
    public ReleaseRequest findById(String releaseRequestId) {
        var doc = getDoc(releaseRequestId);
        return doc == null ? null : toRequest(doc);
    }

    @Override
    public void save(ReleaseRequest request) {
        var existing = getDoc(request.releaseRequestId());
        var doc = mapper.createObjectNode();
        doc.put("_id", request.releaseRequestId());
        doc.put("type", "releaseRequest");
        doc.put("releaseRequestId", request.releaseRequestId());
        doc.put("purpose", request.purpose());
        doc.put("recipientClass", request.recipientClass());
        var fields = doc.putArray("fields");
        request.fields().forEach(fields::add);
        doc.put("siteId", request.siteId());
        doc.put("requestedAt", request.requestedAt().toString());
        if (existing != null && existing.hasNonNull("_rev")) {
            doc.put("_rev", existing.get("_rev").asText());
        }
        put(request.releaseRequestId(), doc);
    }

    @Override
    public List<ReleaseRequest> all() {
        var docs = find("{\"selector\":{\"type\":\"releaseRequest\"},\"limit\":" + MAX_RESULTS + "}");
        List<ReleaseRequest> result = new ArrayList<>();
        for (var doc : docs.path("docs")) {
            result.add(toRequest(doc));
        }
        return result;
    }

    private ReleaseRequest toRequest(JsonNode doc) {
        List<String> fields = new ArrayList<>();
        for (var node : doc.path("fields")) {
            fields.add(node.asText());
        }
        return new ReleaseRequest(doc.get("releaseRequestId").asText(), doc.get("purpose").asText(),
                doc.get("recipientClass").asText(), fields, doc.get("siteId").asText(),
                Instant.parse(doc.get("requestedAt").asText()));
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB get failed with status " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EdcException("CouchDB get failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB get interrupted", e);
        }
    }

    private void put(String id, JsonNode doc) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 200) {
                throw new EdcException("CouchDB put failed with status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("CouchDB put failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB put interrupted", e);
        }
    }

    private JsonNode find(String selectorJson) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_find"))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(selectorJson)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB query failed with status " + response.statusCode() + ": " + response.body());
            }
            var result = mapper.readTree(response.body());
            if (result.path("docs").size() == MAX_RESULTS) {
                throw new EdcException("CouchDB query may have been truncated at the limit of " + MAX_RESULTS + " results");
            }
            return result;
        } catch (IOException e) {
            throw new EdcException("CouchDB query failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB query interrupted", e);
        }
    }
}
