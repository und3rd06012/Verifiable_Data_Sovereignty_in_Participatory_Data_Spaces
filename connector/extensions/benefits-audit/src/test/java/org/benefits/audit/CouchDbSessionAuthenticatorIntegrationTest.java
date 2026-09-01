package org.benefits.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbSessionAuthenticatorIntegrationTest {

    private final CouchDbSessionAuthenticator authenticator =
            new CouchDbSessionAuthenticator("http://localhost:5984", new ObjectMapper());

    @Test
    void resolvesTheAuthenticatedAdminUsername() {
        var header = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
        assertThat(authenticator.authenticate(header)).isEqualTo("admin");
    }

    @Test
    void returnsNullForAMissingHeader() {
        assertThat(authenticator.authenticate(null)).isNull();
    }

    @Test
    void returnsNullForBadCredentials() {
        var header = "Basic " + Base64.getEncoder().encodeToString("admin:wrong-password".getBytes());
        assertThat(authenticator.authenticate(header)).isNull();
    }
}
