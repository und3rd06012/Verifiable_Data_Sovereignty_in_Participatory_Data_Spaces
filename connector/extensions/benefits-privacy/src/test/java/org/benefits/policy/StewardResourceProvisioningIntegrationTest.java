package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for review-round fix 1: re-provisioning an already-provisioned
 * participant must not hand back a freshly generated password with a 201 as if it had
 * been set -- CouchDB's PUT to an existing _users document without its current _rev
 * returns 409 and leaves the stored password untouched, so StewardResource must report
 * that honestly instead of claiming success with credentials that will never authenticate.
 */
class StewardResourceProvisioningIntegrationTest {

    private static final StewardAuthenticator ALWAYS_STEWARD_S1 = header -> "s-1";

    @Test
    void reProvisioningAnExistingParticipantDoesNotClaimAFalsePassword() {
        var participantRef = "p-provision-test-" + System.nanoTime();
        var provisioner = new CouchDbUserProvisioner("http://localhost:5984", "admin", "benefits", new ObjectMapper());
        var resource = new StewardResource(new InMemoryStewardAuthorizationStore(), new InMemoryReleaseRequestStore(),
                ALWAYS_STEWARD_S1, provisioner);

        var first = resource.provisionParticipant(participantRef, "Basic irrelevant");
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getEntity()).isInstanceOf(StewardResource.ProvisionedCredentials.class);
        var firstCredentials = (StewardResource.ProvisionedCredentials) first.getEntity();
        assertThat(firstCredentials.password()).isNotBlank();

        var second = resource.provisionParticipant(participantRef, "Basic irrelevant");
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getEntity()).isInstanceOf(StewardResource.ProvisionStatus.class);
    }
}
