package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;

/**
 * Creates a CouchDB user via the standard /_users/org.couchdb.user:<name> PUT
 * convention, authenticated as a CouchDB admin. A PUT to an already-existing user
 * document without its current _rev is rejected by CouchDB with 409 and does NOT
 * apply the given password, so 201 and 409 are distinguished via the returned
 * Outcome rather than collapsed into a single "success" (the caller must not claim
 * a password was set when it wasn't).
 */
public class CouchDbUserProvisioner {

    public enum Outcome {
        CREATED,
        ALREADY_EXISTS
    }

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbUserProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
    }

    public Outcome provision(String username, String password, List<String> roles) {
        var body = mapper.createObjectNode();
        body.put("name", username);
        body.put("password", password);
        var rolesArray = body.putArray("roles");
        roles.forEach(rolesArray::add);
        body.put("type", "user");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_users/org.couchdb.user:" + username))
                    .header("authorization", adminAuthHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                return Outcome.CREATED;
            }
            if (response.statusCode() == 409) {
                return Outcome.ALREADY_EXISTS;
            }
            throw new EdcException("failed to provision CouchDB user " + username + ": "
                    + response.statusCode() + " " + response.body());
        } catch (IOException e) {
            throw new EdcException("failed to provision CouchDB user " + username, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted provisioning CouchDB user " + username, e);
        }
    }
}
