package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CouchDbBudgetAccountantIntegrationTest {

    private CouchDbBudgetAccountant accountant;

    @BeforeEach
    void setUp() {
        accountant = new CouchDbBudgetAccountant("http://localhost:5984", "benefits-budget-test",
                "admin", "benefits", new ObjectMapper(), 1.0);
    }

    @Test
    void remainingStartsAtTotalForAnUnseenSite() {
        assertThat(accountant.remaining("site-fresh-" + System.nanoTime())).isEqualTo(1.0);
    }

    @Test
    void reserveDeductsFromRemaining() {
        var site = "site-deduct-" + System.nanoTime();
        assertThat(accountant.reserve(site, 0.3)).isTrue();
        assertThat(accountant.remaining(site)).isEqualTo(0.7);
    }

    @Test
    void reserveFailsAndChargesNothingWhenInsufficient() {
        var site = "site-insufficient-" + System.nanoTime();
        accountant.reserve(site, 0.9);
        assertThat(accountant.reserve(site, 0.5)).isFalse();
        assertThat(accountant.remaining(site)).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void reservePersistsAcrossInstances() {
        var site = "site-persist-" + System.nanoTime();
        accountant.reserve(site, 0.4);
        var reloaded = new CouchDbBudgetAccountant("http://localhost:5984", "benefits-budget-test",
                "admin", "benefits", new ObjectMapper(), 1.0);
        assertThat(reloaded.remaining(site)).isEqualTo(0.6);
    }

    @Test
    void concurrentReservesNeverOverspend() throws InterruptedException {
        var site = "site-concurrent-" + System.nanoTime();
        var pool = Executors.newFixedThreadPool(4);
        var go = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);

        for (var i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    if (accountant.reserve(site, 0.3)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        // total=1.0, epsilon=0.3 per reserve: at most 3 of the 4 concurrent attempts
        // can succeed (3 * 0.3 = 0.9 <= 1.0 < 4 * 0.3 = 1.2). If the accountant is not
        // truly atomic, more than 3 can succeed, overspending the budget.
        assertThat(successCount.get()).isLessThanOrEqualTo(3);
        assertThat(accountant.remaining(site)).isGreaterThanOrEqualTo(0.0);
    }
}
