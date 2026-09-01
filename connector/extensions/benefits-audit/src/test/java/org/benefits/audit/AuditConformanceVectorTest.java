package org.benefits.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditConformanceVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path vectorDir() {
        return Path.of(System.getProperty("user.dir")).resolve("../../../conformance/audit-vectors").normalize();
    }

    @TestFactory
    Stream<DynamicTest> everyVectorHashesIdentically() throws IOException {
        try (var files = Files.list(vectorDir())) {
            List<Path> paths = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(paths).isNotEmpty();
            return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                JsonNode vector = MAPPER.readTree(path.toFile());
                List<AuditEntry> entries = new ArrayList<>();
                for (var node : vector.get("entries")) {
                    entries.add(new AuditEntry(
                            node.get("seq").asLong(), node.get("epoch").asText(), node.get("timestamp").asText(),
                            node.get("eventType").asText(), node.get("actor").asText(), node.get("datasetRef").asText(),
                            node.get("statedPurpose").asText(), node.get("plainLanguage").asText()));
                }
                var leaves = entries.stream().map(AuditHashing::leafHash).toList();
                assertThat(leaves.get(0)).isEqualTo(vector.get("expected").get("firstLeafHash").asText());
                assertThat(MerkleTree.root(leaves)).isEqualTo(vector.get("expected").get("root").asText());
            }));
        }
    }
}
