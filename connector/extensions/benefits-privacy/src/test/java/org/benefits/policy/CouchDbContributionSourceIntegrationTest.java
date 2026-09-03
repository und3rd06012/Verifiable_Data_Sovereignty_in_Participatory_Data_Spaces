package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbContributionSourceIntegrationTest {

    private static final String BASE = "http://localhost:5984";
    private static final String DB = "benefits-privacy-it";
    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("admin:benefits".getBytes());
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static void send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("authorization", AUTH).header("content-type", "application/json");
        var request = body == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
                : builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @BeforeAll
    static void setUp() throws Exception {
        send("DELETE", "/" + DB, null);
        send("PUT", "/" + DB, null);
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-1","siteId":"athens",
                 "channel":"app","fields":{"service.satisfaction":4}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-2","siteId":"athens",
                 "channel":"sms","fields":{"service.satisfaction":5}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-3","siteId":"mytilene",
                 "channel":"app","fields":{"service.satisfaction":3}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-4","siteId":"athens",
                 "channel":"paper",
                 "consent":{"participantRef":"p-4","grants":[
                     {"field":"channel","purpose":"community-insight","recipientClass":"community",
                      "grantedAt":"2020-01-01T00:00:00Z","expiresAt":null,"revokedAt":null}
                 ],"assertedAt":"2020-01-01T00:00:00Z"}}""");
    }

    @AfterAll
    static void tearDown() throws Exception {
        send("DELETE", "/" + DB, null);
    }

    @Test
    void readsOnlyContributionsForTheRequestedSite() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var athens = source.contributionsFor("athens");
        assertThat(athens).hasSize(3);
        assertThat(athens).extracting(Contribution::participantRef)
                .containsExactlyInAnyOrder("p-1", "p-2", "p-4");
    }

    @Test
    void exposesQuasiIdentifiersAsAttributes() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var athens = source.contributionsFor("athens");
        assertThat(athens).extracting(c -> c.attribute("channel"))
                .containsExactlyInAnyOrder("app", "sms", "paper");
    }

    @Test
    void returnsEmptyForAnUnknownSite() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        assertThat(source.contributionsFor("nowhere")).isEmpty();
    }

    @Test
    void escapesASiteIdContainingAQuote() throws Exception {
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-quote","siteId":"athens\\"malicious",
                 "channel":"app","fields":{"service.satisfaction":2}}""");

        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var results = source.contributionsFor("athens\"malicious");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).participantRef()).isEqualTo("p-quote");
        assertThat(results.get(0).siteId()).isEqualTo("athens\"malicious");
    }

    @Test
    void skipsADocumentWhoseChannelIsAJsonObjectRatherThanAString() throws Exception {
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-nested","siteId":"chania",
                 "channel":{"unexpected":"object"},"fields":{"service.satisfaction":1}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-chania","siteId":"chania",
                 "channel":"app","fields":{"service.satisfaction":4}}""");

        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var results = source.contributionsFor("chania");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).participantRef()).isEqualTo("p-chania");
        assertThat(results.get(0).attribute("channel")).isEqualTo("app");
    }

    @Test
    void skipsADocumentMissingParticipantRef() throws Exception {
        send("POST", "/" + DB, """
                {"type":"contribution","siteId":"corfu",
                 "channel":"app","fields":{"service.satisfaction":1}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":null,"siteId":"corfu",
                 "channel":"app","fields":{"service.satisfaction":2}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-corfu","siteId":"corfu",
                 "channel":"app","fields":{"service.satisfaction":5}}""");

        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var results = source.contributionsFor("corfu");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).participantRef()).isEqualTo("p-corfu");
    }

    @Test
    void readsConsentGrantsFromTheDocumentIntoContribution() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var athens = source.contributionsFor("athens");

        var p4 = athens.stream().filter(c -> c.participantRef().equals("p-4")).findFirst().orElseThrow();
        assertThat(p4.consentGrants()).hasSize(1);
        assertThat(p4.consentGrants().get(0).field()).isEqualTo("channel");
        assertThat(p4.consentGrants().get(0).purpose()).isEqualTo("community-insight");

        // p-1/p-2 (the pre-existing fixtures, no "consent" field in their documents at
        // all) must still parse with consentGrants() == null, not an empty list: the
        // production write path (packages/consent-couchdb) always writes a consent
        // object today, but this distinction still matters for any document that
        // predates that write path or was written by a test fixture without one.
        var p1 = athens.stream().filter(c -> c.participantRef().equals("p-1")).findFirst().orElseThrow();
        assertThat(p1.consentGrants()).isNull();
    }
}
