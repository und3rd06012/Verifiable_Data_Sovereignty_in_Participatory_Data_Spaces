package org.benefits.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Creates a participant's own private CouchDB database, locks its _security to
 * exactly that one participant, and installs the same identity-check design
 * document the shared database already carries, installed here per-database
 * instead of once, so it still stops a participant's document from claiming a
 * different participantRef even though _security already stops anyone else from
 * writing here at all.
 */
public class CouchDbParticipantDatabaseProvisioner {

    public enum Outcome { CREATED, ALREADY_EXISTS }

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final JsonNode designDoc;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbParticipantDatabaseProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
        this.designDoc = loadDesignDoc(mapper);
    }

    private static JsonNode loadDesignDoc(ObjectMapper mapper) {
        try (InputStream in = CouchDbParticipantDatabaseProvisioner.class.getResourceAsStream("/consent-design-doc.json")) {
            if (in == null) {
                throw new EdcException("consent-design-doc.json resource not found on classpath");
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new EdcException("failed to load consent-design-doc.json", e);
        }
    }

    public String databaseNameFor(String participantRef) {
        return "participant-" + participantRef;
    }

    public Outcome provision(String participantRef) {
        var dbName = databaseNameFor(participantRef);
        var outcome = createDatabase(dbName);
        setSecurity(dbName, participantRef);
        installDesignDoc(dbName);
        return outcome;
    }

    private Outcome createDatabase(String dbName) {
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + dbName))
                .header("authorization", adminAuthHeader)
                .PUT(HttpRequest.BodyPublishers.noBody()));
        if (response.statusCode() == 201) {
            return Outcome.CREATED;
        }
        if (response.statusCode() == 412) {
            return Outcome.ALREADY_EXISTS;
        }
        throw new EdcException("failed to create participant database " + dbName + ": "
                + response.statusCode() + " " + response.body());
    }

    private void setSecurity(String dbName, String participantRef) {
        var body = mapper.createObjectNode();
        var members = body.putObject("members");
        members.putArray("names").add(participantRef);
        members.putArray("roles");
        var admins = body.putObject("admins");
        admins.putArray("names");
        admins.putArray("roles").add("_admin");
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + dbName + "/_security"))
                .header("authorization", adminAuthHeader).header("content-type", "application/json")
                .PUT(bodyOf(body)));
        if (response.statusCode() != 200) {
            throw new EdcException("failed to set _security on " + dbName + ": "
                    + response.statusCode() + " " + response.body());
        }
    }

    private void installDesignDoc(String dbName) {
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + dbName + "/" + designDoc.get("_id").asText()))
                .header("authorization", adminAuthHeader).header("content-type", "application/json")
                .PUT(bodyOf(designDoc)));
        // 409 here means the identical design doc is already installed from a prior
        // provisioning call, its content never varies per participant, so this is a
        // no-op, not a failure (unlike CouchDbUserProvisioner's password case, there is
        // no distinct value at risk of being silently un-applied).
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 409) {
            throw new EdcException("failed to install design doc on " + dbName + ": "
                    + response.statusCode() + " " + response.body());
        }
    }

    private HttpRequest.BodyPublisher bodyOf(JsonNode body) {
        try {
            return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new EdcException("failed to serialize request body", e);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EdcException("CouchDB request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted during CouchDB request", e);
        }
    }
}
