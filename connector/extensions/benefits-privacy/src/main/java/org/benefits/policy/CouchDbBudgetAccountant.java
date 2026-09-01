package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * CouchDB-backed differential privacy budget accountant, one document per site id.
 * reserve() uses a read-check-PUT-with-_rev-retry-on-409 loop: the budget check must
 * happen fresh on every retry attempt, not once before the loop, or a concurrent
 * reservation that lands between this call's check and a later attempt's write would
 * let both succeed and overspend -- exactly the violation BudgetAccountant's own doc
 * comment says reserve must never allow.
 */
public class CouchDbBudgetAccountant implements BudgetAccountant {

    private static final int MAX_RETRIES = 20;

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final double total;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbBudgetAccountant(String baseUrl, String database, String user, String password,
                                    ObjectMapper mapper, double total) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        this.total = total;
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
    public boolean reserve(String siteId, double epsilon) {
        if (epsilon <= 0) {
            return false;
        }
        for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
            var doc = getDoc(siteId);
            var current = doc != null ? doc.get("remaining").asDouble() : total;
            if (current < epsilon) {
                return false;
            }
            var next = current - epsilon;
            var body = mapper.createObjectNode();
            body.put("_id", siteId);
            body.put("type", "budget");
            body.put("siteId", siteId);
            body.put("remaining", next);
            var rev = doc != null && doc.hasNonNull("_rev") ? doc.get("_rev").asText() : null;
            if (rev != null) {
                body.put("_rev", rev);
            }
            var status = put(siteId, body);
            if (status == 200 || status == 201) {
                return true;
            }
            if (status != 409) {
                throw new EdcException("CouchDB put failed with status " + status + " reserving budget for " + siteId);
            }
        }
        throw new EdcException("exceeded retry limit reserving budget for " + siteId);
    }

    @Override
    public double remaining(String siteId) {
        var doc = getDoc(siteId);
        return doc != null ? doc.get("remaining").asDouble() : total;
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

    private int put(String id, JsonNode doc) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (IOException e) {
            throw new EdcException("CouchDB put failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB put interrupted", e);
        }
    }
}
