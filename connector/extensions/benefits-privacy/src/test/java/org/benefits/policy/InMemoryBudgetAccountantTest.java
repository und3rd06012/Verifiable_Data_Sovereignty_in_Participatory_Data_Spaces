package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBudgetAccountantTest {

    @Test
    void startsWithTheConfiguredTotal() {
        var accountant = new InMemoryBudgetAccountant(2.0);
        assertThat(accountant.remaining("athens")).isEqualTo(2.0);
    }

    @Test
    void reservesAndDecrements() {
        var accountant = new InMemoryBudgetAccountant(2.0);
        assertThat(accountant.reserve("athens", 0.5)).isTrue();
        assertThat(accountant.remaining("athens")).isEqualTo(1.5);
    }

    @Test
    void refusesWhenTheRemainingBudgetIsInsufficient() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 0.6)).isTrue();
        assertThat(accountant.reserve("athens", 0.6)).isFalse();
        assertThat(accountant.remaining("athens")).isEqualTo(0.4);
    }

    @Test
    void allowsAReservationExactlyExhaustingTheBudget() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 1.0)).isTrue();
        assertThat(accountant.remaining("athens")).isEqualTo(0.0);
        assertThat(accountant.reserve("athens", 0.0001)).isFalse();
    }

    @Test
    void tracksSitesIndependently() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 1.0)).isTrue();
        assertThat(accountant.reserve("mytilene", 1.0)).isTrue();
    }

    @Test
    void refusesANonPositiveReservation() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 0.0)).isFalse();
        assertThat(accountant.reserve("athens", -0.5)).isFalse();
        assertThat(accountant.remaining("athens")).isEqualTo(1.0);
    }

    @Test
    void concurrentReservationsNeverOverspend() throws Exception {
        // 2.0 total, 0.5 each, so exactly 4 of 50 racing threads may succeed.
        // The race is repeated because a single round is a probabilistic sample: a
        // read-then-write implementation passes a single round most of the time and
        // only overspends when two threads interleave in the window between the read
        // and the write. Repeating turns a weak detector into a reliable one.
        for (var round = 0; round < 20; round++) {
            var accountant = new InMemoryBudgetAccountant(2.0);
            var threads = 50;
            var succeeded = new AtomicInteger();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var pool = Executors.newFixedThreadPool(threads);

            for (var i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (accountant.reserve("athens", 0.5)) {
                            succeeded.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(succeeded.get()).as("round %d", round).isEqualTo(4);
            assertThat(accountant.remaining("athens")).as("round %d", round).isEqualTo(0.0);
        }
    }
}
