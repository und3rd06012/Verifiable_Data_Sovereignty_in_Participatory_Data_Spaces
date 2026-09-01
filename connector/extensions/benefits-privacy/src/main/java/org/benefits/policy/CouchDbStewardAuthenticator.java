package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class CouchDbStewardAuthenticator implements StewardAuthenticator {

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbStewardAuthenticator(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    @Override
    public String authenticateSteward(String authorizationHeader) {
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
            var userCtx = mapper.readTree(response.body()).path("userCtx");
            var name = userCtx.path("name");
            var isSteward = false;
            for (var role : userCtx.path("roles")) {
                if ("steward".equals(role.asText())) {
                    isSteward = true;
                    break;
                }
            }
            return name.isTextual() && isSteward ? name.asText() : null;
        } catch (IOException e) {
            throw new EdcException("CouchDB session check failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB session check interrupted", e);
        }
    }
}
