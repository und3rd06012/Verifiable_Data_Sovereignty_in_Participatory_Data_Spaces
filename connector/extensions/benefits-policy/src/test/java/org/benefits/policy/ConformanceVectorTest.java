package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConformanceVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path vectorDir() {
        return Path.of(System.getProperty("user.dir")).resolve("../../../conformance/vectors").normalize();
    }

    @TestFactory
    Stream<DynamicTest> everyVectorIsDecidedIdentically() throws IOException {
        try (var files = Files.list(vectorDir())) {
            List<Path> paths = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(paths).isNotEmpty();
            return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                JsonNode vector = MAPPER.readTree(path.toFile());
                String reason = ConsentEvaluator.evaluate(vector.get("record"), vector.get("now").asText());
                assertThat(reason).isEqualTo(vector.get("expected").get("reason").asText());
            }));
        }
    }
}
