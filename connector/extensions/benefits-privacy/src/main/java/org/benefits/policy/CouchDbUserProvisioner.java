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
 * convention, authenticated as a CouchDB admin. 201 (created) and 409 (already
 * exists, e.g. re-provisioning) are both treated as success -- provisioning is
 * idempotent by design, matching the reasoning in Task 1's ensureCouchDbUser test
 * helper.
 */
public class CouchDbUserProvisioner {

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbUserProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
    }

    public void provision(String username, String password, List<String> roles) {
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
            if (response.statusCode() != 201 && response.statusCode() != 409) {
                throw new EdcException("failed to provision CouchDB user " + username + ": "
                        + response.statusCode() + " " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to provision CouchDB user " + username, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted provisioning CouchDB user " + username, e);
        }
    }
}
