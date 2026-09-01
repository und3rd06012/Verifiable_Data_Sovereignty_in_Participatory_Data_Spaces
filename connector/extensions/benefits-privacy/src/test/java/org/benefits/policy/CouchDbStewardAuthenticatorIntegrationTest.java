package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbStewardAuthenticatorIntegrationTest {

    private static final String ADMIN_AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    private final CouchDbStewardAuthenticator authenticator =
            new CouchDbStewardAuthenticator("http://localhost:5984", new ObjectMapper());

    @BeforeAll
    static void provisionUsers() throws IOException, InterruptedException {
        provisionUser("s-integration-test", "steward-pw", "[\"steward\"]");
        provisionUser("p-integration-test", "participant-pw", "[]");
    }

    private static void provisionUser(String name, String password, String rolesJson) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var body = "{\"name\":\"" + name + "\",\"password\":\"" + password + "\",\"roles\":" + rolesJson + ",\"type\":\"user\"}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:5984/_users/org.couchdb.user:" + name))
                .header("authorization", ADMIN_AUTH).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IllegalStateException("failed to provision test user " + name + ": " + response.statusCode());
        }
    }

    @Test
    void authenticatesAUserWithTheStewardRole() {
        var header = "Basic " + Base64.getEncoder().encodeToString("s-integration-test:steward-pw".getBytes());
        assertThat(authenticator.authenticateSteward(header)).isEqualTo("s-integration-test");
    }

    @Test
    void rejectsAValidUserWithoutTheStewardRole() {
        var header = "Basic " + Base64.getEncoder().encodeToString("p-integration-test:participant-pw".getBytes());
        assertThat(authenticator.authenticateSteward(header)).isNull();
    }

    @Test
    void rejectsAMissingHeader() {
        assertThat(authenticator.authenticateSteward(null)).isNull();
    }
}
