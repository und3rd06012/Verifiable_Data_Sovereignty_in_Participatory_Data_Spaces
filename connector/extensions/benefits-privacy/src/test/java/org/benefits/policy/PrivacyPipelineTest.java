package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyPipelineTest {

    private List<Contribution> participants(String channel, int n, String prefix) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Contribution(prefix + i, "athens", Map.of("channel", channel)))
                .toList();
    }

    private PrivacyPipeline pipeline(List<Contribution> rows, BudgetAccountant accountant, long seed) {
        return new PrivacyPipeline(new InMemoryContributionSource(rows),
                new LaplaceNoiseSource(new Random(seed)), accountant);
    }

    private final QuerySpec byChannel = new QuerySpec("athens", List.of("channel"));

    @Test
    void producesAnArtifactWhenBudgetAllows() {
        var pipeline = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 42);
        var artifact = pipeline.compute("rr-1", byChannel, 5, 0.5);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().releaseRequestId()).isEqualTo("rr-1");
        assertThat(artifact.get().epsilonCharged()).isEqualTo(0.5);
    }

    @Test
    void refusesWhenTheBudgetIsExhausted() {
        var accountant = new InMemoryBudgetAccountant(0.4);
        var pipeline = pipeline(participants("app", 6, "a"), accountant, 42);
        assertThat(pipeline.compute("rr-1", byChannel, 5, 0.5)).isEmpty();
        assertThat(accountant.remaining("athens")).isEqualTo(0.4);
    }

    @Test
    void doesNotChargeBudgetWhenItRefuses() {
        var accountant = new InMemoryBudgetAccountant(0.4);
        pipeline(participants("app", 6, "a"), accountant, 42).compute("rr-1", byChannel, 5, 0.5);
        assertThat(accountant.remaining("athens")).isEqualTo(0.4);
    }

    @Test
    void reportsComputedKAsTheSmallestSurvivingGroup() {
        var rows = new ArrayList<Contribution>(participants("app", 9, "a"));
        rows.addAll(participants("sms", 6, "s"));
        var artifact = pipeline(rows, new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().computedK()).isEqualTo(6);
    }

    @Test
    void reportsComputedKAsZeroWhenEverythingIsSuppressed() {
        var artifact = pipeline(participants("app", 3, "a"), new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().computedK()).isZero();
    }

    @Test
    void isDeterministicForAGivenSeed() {
        var a = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 99)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var b = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 99)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        assertThat(a.bytes()).isEqualTo(b.bytes());
    }

    @Test
    void addsNoiseSoTheOutputIsNotTheTrueCount() throws Exception {
        var artifact = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var text = new String(artifact.bytes(), StandardCharsets.UTF_8);
        assertThat(text).contains("app");

        var mapper = new ObjectMapper();
        var root = mapper.readTree(artifact.bytes());
        var count = root.get("groups").get(0).get("count").asDouble();
        assertThat(count).isNotEqualTo(6.0);
    }

    @Test
    void jsonEscapesAGroupKeyContainingThePipeSeparator() throws Exception {
        var rows = participants("a|b", 6, "a");
        var artifact = pipeline(rows, new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var expectedGroupKey = KAnonymity.aggregate(rows, byChannel, 5).get(0).groupKey();

        var mapper = new ObjectMapper();
        var root = mapper.readTree(artifact.bytes());
        assertThat(root.get("groups").get(0).get("group").asText()).isEqualTo(expectedGroupKey);
    }

    @Test
    void jsonEscapesAGroupKeyContainingAQuote() throws Exception {
        var rows = participants("a\"b", 6, "a");
        var artifact = pipeline(rows, new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var expectedGroupKey = KAnonymity.aggregate(rows, byChannel, 5).get(0).groupKey();

        var mapper = new ObjectMapper();
        var root = mapper.readTree(artifact.bytes());
        assertThat(root.get("groups").get(0).get("group").asText()).isEqualTo(expectedGroupKey);
    }

    @Test
    void jsonEscapesAGroupKeyContainingABackslash() throws Exception {
        var rows = participants("a\\b", 6, "a");
        var artifact = pipeline(rows, new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var expectedGroupKey = KAnonymity.aggregate(rows, byChannel, 5).get(0).groupKey();

        var mapper = new ObjectMapper();
        var root = mapper.readTree(artifact.bytes());
        assertThat(root.get("groups").get(0).get("group").asText()).isEqualTo(expectedGroupKey);
    }
}
