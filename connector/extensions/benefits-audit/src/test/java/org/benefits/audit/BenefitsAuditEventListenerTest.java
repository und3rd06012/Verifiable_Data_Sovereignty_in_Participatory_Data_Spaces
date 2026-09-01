package org.benefits.audit;

import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationTerminated;
import org.eclipse.edc.connector.controlplane.contract.spi.types.agreement.ContractAgreement;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractOffer;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenefitsAuditEventListenerTest {

    private Policy policyWithPurpose(String purpose) {
        var constraint = AtomicConstraint.Builder.newInstance()
                .leftExpression(new LiteralExpression(org.benefits.policy.BenefitsNamespace.PURPOSE_KEY))
                .operator(Operator.EQ)
                .rightExpression(new LiteralExpression(purpose))
                .build();
        var permission = Permission.Builder.newInstance()
                .action(Action.Builder.newInstance().type("use").build())
                .constraint(constraint)
                .build();
        return Policy.Builder.newInstance().permission(permission).build();
    }

    @Test
    void finalizedNegotiationProducesAnEntryWithActorAssetAndPurpose() {
        var agreement = ContractAgreement.Builder.newInstance()
                .id("agreement-1").providerId("provider").consumerId("consumer")
                .assetId("asset-1").policy(policyWithPurpose("service-improvement"))
                .build();
        var event = ContractNegotiationFinalized.Builder.newInstance()
                .contractNegotiationId("neg-1").counterPartyId("participant-a")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractAgreement(agreement).build();

        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);
        listener.onFinalized(event);

        var recorded = store.unsealedEntries().get(0);
        assertThat(recorded.actor()).isEqualTo("participant-a");
        assertThat(recorded.datasetRef()).isEqualTo("asset-1");
        assertThat(recorded.statedPurpose()).isEqualTo("service-improvement");
        assertThat(recorded.eventType()).isEqualTo("negotiation-finalized");
    }

    @Test
    void terminatedNegotiationReadsAssetAndPurposeFromTheLastOffer() {
        var offer = ContractOffer.Builder.newInstance().id("offer-1").assetId("asset-2")
                .policy(policyWithPurpose("advocacy")).build();
        var event = ContractNegotiationTerminated.Builder.newInstance()
                .contractNegotiationId("neg-2").counterPartyId("participant-b")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractOffers(java.util.List.of(offer)).build();

        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);
        listener.onTerminated(event);

        var recorded = store.unsealedEntries().get(0);
        assertThat(recorded.actor()).isEqualTo("participant-b");
        assertThat(recorded.datasetRef()).isEqualTo("asset-2");
        assertThat(recorded.statedPurpose()).isEqualTo("advocacy");
        assertThat(recorded.eventType()).isEqualTo("negotiation-terminated");
    }

    @Test
    void transferCompletedCorrelatesActorAndPurposeFromThePriorNegotiationEntry() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);

        var agreement = ContractAgreement.Builder.newInstance()
                .id("agreement-3").providerId("provider").consumerId("consumer")
                .assetId("asset-3").policy(policyWithPurpose("statistics"))
                .build();
        var negotiationEvent = ContractNegotiationFinalized.Builder.newInstance()
                .contractNegotiationId("neg-3").counterPartyId("participant-c")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractAgreement(agreement).build();
        listener.onFinalized(negotiationEvent);

        var transferEvent = TransferProcessCompleted.Builder.newInstance()
                .transferProcessId("tp-1").assetId("asset-3").type("PUSH").contractId("agreement-3")
                .build();
        listener.onTransferCompleted(transferEvent);

        var entries = store.entriesFor("participant-c");
        assertThat(entries).hasSize(2);
        var transferEntry = entries.stream().filter(e -> "transfer-completed".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(transferEntry.statedPurpose()).isEqualTo("statistics");
        assertThat(transferEntry.datasetRef()).isEqualTo("asset-3");
    }
}
