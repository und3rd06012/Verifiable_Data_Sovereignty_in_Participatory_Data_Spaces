package org.benefits.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Validates a caller-supplied Basic-Auth header against CouchDB's own /_session
 * endpoint and returns the authenticated username, or null if the header is missing
 * or CouchDB rejects it. Reuses CouchDB's own user store rather than a separate
 * identity provider, the same reasoning as the participant-identity write check in
 * consent-couchdb's build-design-doc.ts.
 */
public class CouchDbSessionAuthenticator implements SessionAuthenticator {

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbSessionAuthenticator(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    @Override
    public String authenticate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_session"))
                    .header("authorization", authorizationHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            var body = mapper.readTree(response.body());
            var name = body.path("userCtx").path("name");
            return name.isTextual() ? name.asText() : null;
        } catch (IOException e) {
            throw new EdcException("CouchDB session check failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB session check interrupted", e);
        }
    }
}
