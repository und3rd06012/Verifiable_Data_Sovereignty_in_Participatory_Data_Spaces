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
import java.util.Base64;

public class CouchDbReleaseArtifactStore implements ReleaseArtifactStore {

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbReleaseArtifactStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
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
    public ReleaseArtifact findByReleaseRequestId(String releaseRequestId) {
        var doc = getDoc(releaseRequestId);
        if (doc == null) {
            return null;
        }
        var bytes = Base64.getDecoder().decode(doc.get("bytesBase64").asText());
        return new ReleaseArtifact(doc.get("releaseRequestId").asText(), bytes,
                doc.get("computedK").asInt(), doc.get("epsilonCharged").asDouble());
    }

    @Override
    public void save(ReleaseArtifact artifact) {
        var existing = getDoc(artifact.releaseRequestId());
        var doc = mapper.createObjectNode();
        doc.put("_id", artifact.releaseRequestId());
        doc.put("type", "releaseArtifact");
        doc.put("releaseRequestId", artifact.releaseRequestId());
        doc.put("bytesBase64", Base64.getEncoder().encodeToString(artifact.bytes()));
        doc.put("computedK", artifact.computedK());
        doc.put("epsilonCharged", artifact.epsilonCharged());
        if (existing != null && existing.hasNonNull("_rev")) {
            doc.put("_rev", existing.get("_rev").asText());
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + artifact.releaseRequestId()))
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
}
