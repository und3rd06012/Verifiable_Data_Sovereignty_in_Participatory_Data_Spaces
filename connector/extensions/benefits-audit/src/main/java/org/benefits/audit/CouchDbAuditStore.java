package org.benefits.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists AuditEntry as CouchDB documents of type "auditEntry" and Epoch as
 * "auditEpoch". The auditEpoch document additionally carries firstSeq and lastSeq,
 * beyond the four fields the public Epoch record exposes, so incompleteEpochs() can
 * find and resume a seal that crashed between writing the epoch and stamping its
 * member entries (AuditStore's class doc, and spec section 4.3).
 */
public class CouchDbAuditStore implements AuditStore {

    private static final int MAX_RESULTS = 100_000;

    private final String baseUrl;
    private final String database;
    private final String authorization;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicLong seqCounter;

    public CouchDbAuditStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        ensureDatabaseExists();
        ensureSeqIndex();
        this.seqCounter = new AtomicLong(loadMaxSeq() + 1);
    }

    /**
     * A CouchDB Mango query needs a matching index to sort by seq. loadMaxSeq's
     * "sort":[{"seq":"desc"}] query fails with a "no matching index" error without
     * one, so this store creates the index itself before it is first needed.
     */
    private void ensureSeqIndex() {
        var body = "{\"index\":{\"fields\":[\"seq\"]},\"name\":\"seq-index\"}";
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_index"))
                    .header("authorization", authorization).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EdcException("failed to create seq index", e);
        }
    }

    /**
     * Unlike CouchDbContributionSource in phase 3, this store is wired directly into a
     * live connector's startup path (BenefitsAuditExtension, Task 6) and exercised by a
     * real connector process (Task 10's end-to-end probe) — phase 3's CouchDB reader
     * never was. Nothing else creates the database first, so this store creates its
     * own, idempotently: 201 means it just did, 412 means another process already did
     * (both are success), anything else is a real failure.
     */
    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authorization).PUT(HttpRequest.BodyPublishers.noBody()).build();
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
    public void append(AuditEntry entry) {
        var doc = mapper.createObjectNode();
        doc.put("_id", "auditEntry-" + entry.seq());
        doc.put("type", "auditEntry");
        doc.put("seq", entry.seq());
        if (entry.epoch() != null) {
            doc.put("epoch", entry.epoch());
        }
        doc.put("timestamp", entry.timestamp());
        doc.put("eventType", entry.eventType());
        doc.put("actor", entry.actor());
        doc.put("datasetRef", entry.datasetRef());
        doc.put("statedPurpose", entry.statedPurpose());
        doc.put("plainLanguage", entry.plainLanguage());
        put("auditEntry-" + entry.seq(), doc);
    }

    @Override
    public long nextSeq() {
        return seqCounter.getAndIncrement();
    }

    @Override
    public List<AuditEntry> unsealedEntries() {
        var docs = find("{\"selector\":{\"type\":\"auditEntry\",\"epoch\":{\"$exists\":false}},\"limit\":" + MAX_RESULTS + "}");
        return parseEntries(docs).stream().sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }

    @Override
    public void sealEpoch(Epoch epoch, long firstSeq, long lastSeq) {
        if (!epochDocExists(epoch.epochId())) {
            var doc = mapper.createObjectNode();
            doc.put("_id", "auditEpoch-" + epoch.epochId());
            doc.put("type", "auditEpoch");
            doc.put("epochId", epoch.epochId());
            doc.put("merkleRoot", epoch.merkleRoot());
            doc.put("prevEpochHash", epoch.prevEpochHash());
            doc.put("sealedAt", epoch.sealedAt());
            doc.put("firstSeq", firstSeq);
            doc.put("lastSeq", lastSeq);
            put("auditEpoch-" + epoch.epochId(), doc);
        }
        for (var seq = firstSeq; seq <= lastSeq; seq++) {
            stampEpoch("auditEntry-" + seq, epoch.epochId());
        }
    }

    @Override
    public List<UnsealedRange> incompleteEpochs() {
        List<UnsealedRange> result = new ArrayList<>();
        var epochDocs = find("{\"selector\":{\"type\":\"auditEpoch\"},\"limit\":" + MAX_RESULTS + "}");
        for (var doc : epochDocs.path("docs")) {
            var epoch = new Epoch(doc.get("epochId").asText(), doc.get("merkleRoot").asText(),
                    doc.get("prevEpochHash").asText(), doc.get("sealedAt").asText());
            var firstSeq = doc.get("firstSeq").asLong();
            var lastSeq = doc.get("lastSeq").asLong();
            var stillNull = false;
            for (var seq = firstSeq; seq <= lastSeq; seq++) {
                var entryDoc = getDoc("auditEntry-" + seq);
                if (entryDoc != null && !entryDoc.hasNonNull("epoch")) {
                    stillNull = true;
                    break;
                }
            }
            if (stillNull) {
                result.add(new UnsealedRange(epoch, firstSeq, lastSeq));
            }
        }
        return result;
    }

    @Override
    public List<AuditEntry> entriesFor(String participantRef) {
        var docs = find(dynamicSelector("actor", participantRef));
        return parseEntries(docs).stream().sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }

    /**
     * Every selector whose value comes from outside this class (a participant-supplied
     * HTTP path parameter, an EDC-supplied asset id, a server-generated epoch id) is
     * built here, through ObjectMapper, never through string interpolation — the exact
     * defect class CouchDbContributionSource (phase 3) had to fix. A hand-rolled
     * ".replace(\"\\\"\", \"\\\\\\\"\")" escape looks safe and is not: it only escapes
     * quotes, not backslashes, so a value ending in a backslash or containing a
     * backslash-quote sequence still breaks the surrounding JSON.
     */
    private String dynamicSelector(String field, String value) {
        var selector = mapper.createObjectNode();
        var criteria = selector.putObject("selector");
        criteria.put("type", "auditEntry");
        criteria.put(field, value);
        selector.put("limit", MAX_RESULTS);
        try {
            return mapper.writeValueAsString(selector);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EdcException("failed to serialize CouchDB selector", e);
        }
    }

    @Override
    public List<Epoch> epochChain() {
        var docs = find("{\"selector\":{\"type\":\"auditEpoch\"},\"limit\":" + MAX_RESULTS + "}");
        List<Epoch> result = new ArrayList<>();
        for (var doc : docs.path("docs")) {
            result.add(new Epoch(doc.get("epochId").asText(), doc.get("merkleRoot").asText(),
                    doc.get("prevEpochHash").asText(), doc.get("sealedAt").asText()));
        }
        result.sort((a, b) -> Long.compare(sealedAtOrdinal(a), sealedAtOrdinal(b)));
        return result;
    }

    private long sealedAtOrdinal(Epoch epoch) {
        return java.time.Instant.parse(epoch.sealedAt()).toEpochMilli();
    }

    private long loadMaxSeq() {
        var docs = find("{\"selector\":{\"type\":\"auditEntry\"},\"sort\":[{\"seq\":\"desc\"}],\"limit\":1}");
        var results = docs.path("docs");
        return results.isEmpty() ? -1 : results.get(0).get("seq").asLong();
    }

    private boolean epochDocExists(String epochId) {
        return getDoc("auditEpoch-" + epochId) != null;
    }

    private void stampEpoch(String docId, String epochId) {
        var existing = getDoc(docId);
        if (existing == null || existing.hasNonNull("epoch")) {
            return;
        }
        var doc = existing.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("epoch", epochId);
        put(docId, doc);
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authorization).GET().build();
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

    private void put(String id, JsonNode doc) {
        try {
            var existing = getDoc(id);
            var body = doc.deepCopy();
            if (existing != null && existing.hasNonNull("_rev")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("_rev", existing.get("_rev").asText());
            }
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authorization).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
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

    private JsonNode find(String selectorJson) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_find"))
                    .header("authorization", authorization).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(selectorJson)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB query failed with status " + response.statusCode() + ": " + response.body());
            }
            var result = mapper.readTree(response.body());
            if (result.path("docs").size() == MAX_RESULTS) {
                throw new EdcException("CouchDB query may have been truncated at the limit of " + MAX_RESULTS + " results");
            }
            return result;
        } catch (IOException e) {
            throw new EdcException("CouchDB query failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB query interrupted", e);
        }
    }

    private List<AuditEntry> parseEntries(JsonNode docs) {
        List<AuditEntry> result = new ArrayList<>();
        for (var doc : docs.path("docs")) {
            result.add(new AuditEntry(
                    doc.get("seq").asLong(),
                    doc.hasNonNull("epoch") ? doc.get("epoch").asText() : null,
                    doc.get("timestamp").asText(),
                    doc.get("eventType").asText(),
                    doc.get("actor").asText(),
                    doc.get("datasetRef").asText(),
                    doc.get("statedPurpose").asText(),
                    doc.get("plainLanguage").asText()));
        }
        return result;
    }
}
