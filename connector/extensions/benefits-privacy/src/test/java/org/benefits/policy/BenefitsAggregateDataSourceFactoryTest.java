package org.benefits.policy;

import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BenefitsAggregateDataSourceFactoryTest {

    private final ReleaseArtifactStore store = new InMemoryReleaseArtifactStore();
    private final BenefitsAggregateDataSourceFactory factory = new BenefitsAggregateDataSourceFactory(store);

    private DataFlowStartMessage messageFor(String releaseRequestId) {
        var address = DataAddress.Builder.newInstance()
                .type(BenefitsAggregateDataSourceFactory.TYPE)
                .property("releaseRequestId", releaseRequestId)
                .build();
        return DataFlowStartMessage.Builder.newInstance()
                .processId("pid")
                .sourceDataAddress(address)
                .destinationDataAddress(DataAddress.Builder.newInstance().type("HttpData").build())
                .build();
    }

    @Test
    void supportsTheBenefitsAggregateType() {
        assertThat(factory.supportedType()).isEqualTo("BenefitsAggregate");
    }

    @Test
    void validationFailsWhenTheAddressCarriesNoReleaseRequestId() {
        var address = DataAddress.Builder.newInstance()
                .type(BenefitsAggregateDataSourceFactory.TYPE).build();
        var message = DataFlowStartMessage.Builder.newInstance()
                .processId("pid").sourceDataAddress(address)
                .destinationDataAddress(DataAddress.Builder.newInstance().type("HttpData").build())
                .build();
        assertThat(factory.validateRequest(message).succeeded()).isFalse();
    }

    @Test
    void validationFailsWhenNoArtifactIsStored() {
        assertThat(factory.validateRequest(messageFor("rr-absent")).succeeded()).isFalse();
    }

    @Test
    void validationSucceedsForAStoredArtifact() {
        store.save(new ReleaseArtifact("rr-1", "{}".getBytes(StandardCharsets.UTF_8), 6, 0.5));
        assertThat(factory.validateRequest(messageFor("rr-1")).succeeded()).isTrue();
    }

    @Test
    void servesTheStoredBytesUnchanged() throws Exception {
        var payload = "{\"groups\":[]}".getBytes(StandardCharsets.UTF_8);
        store.save(new ReleaseArtifact("rr-1", payload, 6, 0.5));
        var source = factory.createSource(messageFor("rr-1"));
        var part = source.openPartStream().getContent().findFirst().orElseThrow();
        assertThat(part.openStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void servesIdenticalBytesOnRepeatedTransfers() throws Exception {
        store.save(new ReleaseArtifact("rr-1", "{\"groups\":[]}".getBytes(StandardCharsets.UTF_8), 6, 0.5));
        var first = factory.createSource(messageFor("rr-1")).openPartStream()
                .getContent().findFirst().orElseThrow().openStream().readAllBytes();
        var second = factory.createSource(messageFor("rr-1")).openPartStream()
                .getContent().findFirst().orElseThrow().openStream().readAllBytes();
        assertThat(first).isEqualTo(second);
    }
}
