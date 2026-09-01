package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Persists StewardAuthorization as one CouchDB document per release request id,
 * keyed by releaseRequestId as _id.
 * <p>
 * The append* methods use a read-check_rev-PUT-retry-on-409 loop, not the plain
 * read-then-write CouchDbAuditStore.put() uses: two stewards co-signing the same
 * release request from concurrent HTTP requests (StewardResource) is exactly the
 * race StewardAuthorizationStore's own interface doc comment requires append to
 * survive without losing either steward's action.
 */
public class CouchDbStewardAuthorizationStore implements StewardAuthorizationStore {

    private static final int MAX_RETRIES = 10;

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbStewardAuthorizationStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
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
    public StewardAuthorization findByReleaseRequestId(String releaseRequestId) {
        var doc = getDoc(releaseRequestId);
        return doc == null ? null : toAuthorization(doc);
    }

    @Override
    public void save(StewardAuthorization authorization) {
        var existing = getDoc(authorization.releaseRequestId());
        var rev = existing != null && existing.hasNonNull("_rev") ? existing.get("_rev").asText() : null;
        var status = putDoc(authorization.releaseRequestId(), authorization, rev);
        if (status != 200 && status != 201) {
            throw new EdcException("CouchDB put failed with status " + status
                    + " saving steward authorization " + authorization.releaseRequestId());
        }
    }

    @Override
    public StewardAuthorization appendReview(String releaseRequestId, String datasetHash, StewardReview review) {
        return appendWithRetry(releaseRequestId, datasetHash, base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), base.signatures(),
                append(base.reviews(), review), base.vetoes(), base.appeals(), base.issuedAt()));
    }

    @Override
    public StewardAuthorization appendSignature(String releaseRequestId, String datasetHash, StewardSignature signature) {
        return appendWithRetry(releaseRequestId, datasetHash, base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), append(base.signatures(), signature),
                base.reviews(), base.vetoes(), base.appeals(), base.issuedAt()));
    }

    @Override
    public StewardAuthorization appendVeto(String releaseRequestId, String datasetHash, StewardVeto veto) {
        return appendWithRetry(releaseRequestId, datasetHash, base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), base.signatures(),
                base.reviews(), append(base.vetoes(), veto), base.appeals(), base.issuedAt()));
    }

    @Override
    public StewardAuthorization appendAppeal(String releaseRequestId, StewardAppeal appeal) {
        var existing = findByReleaseRequestId(releaseRequestId);
        if (existing == null) {
            throw new EdcException("cannot appeal a veto for release request with no steward authorization: " + releaseRequestId);
        }
        var vetoExists = existing.vetoes().stream().anyMatch(v -> v.stewardId().equals(appeal.vetoStewardId()));
        if (!vetoExists) {
            throw new EdcException("no veto by " + appeal.vetoStewardId() + " to appeal for release request " + releaseRequestId);
        }
        return appendWithRetry(releaseRequestId, existing.datasetHash(), base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), base.signatures(),
                base.reviews(), base.vetoes(), append(base.appeals(), appeal), base.issuedAt()));
    }

    private StewardAuthorization appendWithRetry(String releaseRequestId, String datasetHash,
                                                   UnaryOperator<StewardAuthorization> mutate) {
        for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
            var existingDoc = getDoc(releaseRequestId);
            var base = existingDoc != null ? toAuthorization(existingDoc) : empty(releaseRequestId, datasetHash);
            var updated = mutate.apply(base);
            var rev = existingDoc != null && existingDoc.hasNonNull("_rev") ? existingDoc.get("_rev").asText() : null;
            var status = putDoc(releaseRequestId, updated, rev);
            if (status == 200 || status == 201) {
                return updated;
            }
            if (status != 409) {
                throw new EdcException("CouchDB put failed with status " + status + " writing steward authorization " + releaseRequestId);
            }
        }
        throw new EdcException("exceeded retry limit appending to steward authorization " + releaseRequestId);
    }

    private static StewardAuthorization empty(String releaseRequestId, String datasetHash) {
        return new StewardAuthorization(releaseRequestId, datasetHash, List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    private static <T> List<T> append(List<T> list, T item) {
        var copy = new ArrayList<>(list);
        copy.add(item);
        return List.copyOf(copy);
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

    private int putDoc(String id, StewardAuthorization authorization, @Nullable String rev) {
        var doc = toJson(authorization);
        if (rev != null) {
            doc.put("_rev", rev);
        }
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

    private ObjectNode toJson(StewardAuthorization authorization) {
        var doc = mapper.createObjectNode();
        doc.put("_id", authorization.releaseRequestId());
        doc.put("type", "stewardAuthorization");
        doc.put("releaseRequestId", authorization.releaseRequestId());
        doc.put("datasetHash", authorization.datasetHash());
        doc.put("issuedAt", authorization.issuedAt().toString());
        var signatures = doc.putArray("signatures");
        for (var s : authorization.signatures()) {
            signatures.addObject().put("stewardId", s.stewardId()).put("signature", s.signature())
                    .put("signedAt", s.signedAt().toString());
        }
        var reviews = doc.putArray("reviews");
        for (var r : authorization.reviews()) {
            reviews.addObject().put("stewardId", r.stewardId()).put("rubricScore", r.rubricScore())
                    .put("notes", r.notes()).put("reviewedAt", r.reviewedAt().toString());
        }
        var vetoes = doc.putArray("vetoes");
        for (var v : authorization.vetoes()) {
            vetoes.addObject().put("stewardId", v.stewardId()).put("reason", v.reason())
                    .put("vetoedAt", v.vetoedAt().toString());
        }
        var appeals = doc.putArray("appeals");
        for (var a : authorization.appeals()) {
            appeals.addObject().put("appellantId", a.appellantId()).put("reason", a.reason())
                    .put("vetoStewardId", a.vetoStewardId()).put("appealedAt", a.appealedAt().toString());
        }
        return doc;
    }

    private StewardAuthorization toAuthorization(JsonNode doc) {
        List<StewardSignature> signatures = new ArrayList<>();
        for (var node : doc.path("signatures")) {
            signatures.add(new StewardSignature(node.get("stewardId").asText(), node.get("signature").asText(),
                    Instant.parse(node.get("signedAt").asText())));
        }
        List<StewardReview> reviews = new ArrayList<>();
        for (var node : doc.path("reviews")) {
            reviews.add(new StewardReview(node.get("stewardId").asText(), node.get("rubricScore").asInt(),
                    node.get("notes").asText(), Instant.parse(node.get("reviewedAt").asText())));
        }
        List<StewardVeto> vetoes = new ArrayList<>();
        for (var node : doc.path("vetoes")) {
            vetoes.add(new StewardVeto(node.get("stewardId").asText(), node.get("reason").asText(),
                    Instant.parse(node.get("vetoedAt").asText())));
        }
        List<StewardAppeal> appeals = new ArrayList<>();
        for (var node : doc.path("appeals")) {
            appeals.add(new StewardAppeal(node.get("appellantId").asText(), node.get("reason").asText(),
                    node.get("vetoStewardId").asText(), Instant.parse(node.get("appealedAt").asText())));
        }
        return new StewardAuthorization(doc.get("releaseRequestId").asText(), doc.get("datasetHash").asText(),
                signatures, reviews, vetoes, appeals, Instant.parse(doc.get("issuedAt").asText()));
    }
}
