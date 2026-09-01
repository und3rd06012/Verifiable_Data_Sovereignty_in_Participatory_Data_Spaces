package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouchDbStewardAuthorizationStoreIntegrationTest {

    private CouchDbStewardAuthorizationStore store;

    @BeforeEach
    void setUp() {
        store = new CouchDbStewardAuthorizationStore("http://localhost:5984", "benefits-steward-test",
                "admin", "benefits", new ObjectMapper());
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("rr-missing-" + System.nanoTime())).isNull();
    }

    @Test
    void appendSignatureCreatesThenPersistsAcrossInstances() {
        var id = "rr-persist-" + System.nanoTime();
        var signature = new StewardSignature("s-1", "sig-s-1", Instant.parse("2026-09-01T00:00:00Z"));
        store.appendSignature(id, "hash-1", signature);

        var reloaded = new CouchDbStewardAuthorizationStore("http://localhost:5984", "benefits-steward-test",
                "admin", "benefits", new ObjectMapper());
        var found = reloaded.findByReleaseRequestId(id);
        assertThat(found).isNotNull();
        assertThat(found.signatures()).containsExactly(signature);
        assertThat(found.datasetHash()).isEqualTo("hash-1");
    }

    @Test
    void appendAppealFailsWhenNoVetoExists() {
        var id = "rr-appeal-fail-" + System.nanoTime();
        var appeal = new StewardAppeal("p-1", "reason", "s-nobody", Instant.parse("2026-09-01T00:00:00Z"));
        assertThatThrownBy(() -> store.appendAppeal(id, appeal))
                .isInstanceOf(org.eclipse.edc.spi.EdcException.class);
    }

    @Test
    void concurrentSignaturesFromTwoStewardsAreBothPersisted() throws InterruptedException {
        var id = "rr-concurrent-" + System.nanoTime();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        pool.submit(() -> signAsSteward(id, "s-1", ready, go));
        pool.submit(() -> signAsSteward(id, "s-2", ready, go));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        var result = store.findByReleaseRequestId(id);
        assertThat(result).isNotNull();
        assertThat(result.signatures()).hasSize(2);
        assertThat(result.distinctStewardCount()).isEqualTo(2);
    }

    private void signAsSteward(String releaseRequestId, String stewardId, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            store.appendSignature(releaseRequestId, "hash-concurrent",
                    new StewardSignature(stewardId, "sig-" + stewardId, Instant.now()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
