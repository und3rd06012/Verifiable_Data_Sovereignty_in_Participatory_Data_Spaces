package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2: two genuinely concurrent HTTP-shaped calls (two threads calling the
 * resource's coSign method, backed by real CouchDB) must both persist -- this is
 * the direct proof that the atomic-append fix (Task 2, Task 3) actually closed
 * StewardAuthorizationStore's own documented replace-semantics race, not just a
 * unit-level ConcurrentHashMap test.
 */
class StewardResourceConcurrencyIntegrationTest {

    @Test
    void twoConcurrentCoSignsBothPersistToCouchDb() throws InterruptedException {
        var id = "rr-http-concurrent-" + System.nanoTime();
        var authorizationStore = new CouchDbStewardAuthorizationStore("http://localhost:5984",
                "benefits-steward-test", "admin", "benefits", new ObjectMapper());
        var requestStore = new InMemoryReleaseRequestStore();
        StewardAuthenticator authenticatorS1 = header -> "s-1";
        StewardAuthenticator authenticatorS2 = header -> "s-2";
        var resourceAsS1 = new StewardResource(authorizationStore, requestStore, authenticatorS1, null);
        var resourceAsS2 = new StewardResource(authorizationStore, requestStore, authenticatorS2, null);

        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                go.await();
                resourceAsS1.coSign(id, "Basic irrelevant", new StewardResource.CoSignBody("hash-http", "sig-s-1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                go.await();
                resourceAsS2.coSign(id, "Basic irrelevant", new StewardResource.CoSignBody("hash-http", "sig-s-2"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        var authorization = authorizationStore.findByReleaseRequestId(id);
        assertThat(authorization).isNotNull();
        assertThat(authorization.distinctStewardCount()).isEqualTo(2);
    }
}
