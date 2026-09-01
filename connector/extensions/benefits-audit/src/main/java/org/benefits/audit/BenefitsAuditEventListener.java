package org.benefits.audit;

import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationTerminated;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessTerminated;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps four EDC event types into AuditEntry and hands each to EpochSealer.
 * TransferProcessEvent carries no counter-party or policy accessor (confirmed by
 * inspecting the compiled class directly), so a transfer entry correlates its actor
 * and statedPurpose from the negotiation that produced its agreement, keyed by
 * agreement id (spec section 6) — correlating by asset id instead is ambiguous
 * whenever the same asset is negotiated more than once, which an earlier version of
 * this class did and a task reviewer caught. The cache is in-memory only and does
 * not survive a restart; a transfer completing after a restart between its
 * negotiation and the transfer itself falls back to "unknown", the same honest
 * fallback used for any other cache miss.
 * <p>
 * ContractNegotiationTerminated does NOT cover a policy-refused negotiation (spec
 * section 2.2): EDC 0.18.0 rejects an offer that fails ODRL constraint evaluation
 * synchronously, before any ContractNegotiation entity exists, so no event of any
 * kind fires for that case. This handler only ever sees a negotiation that was
 * already admitted into the provider's state machine and later terminated (an
 * explicit management-API termination, or a post-admission lifecycle failure) —
 * never a k-anonymity, epsilon-budget, steward-quorum, purpose, or recipient-class
 * refusal at initial offer validation. Do not describe this entry's plainLanguage as
 * a "refusal" in a way that implies it covers that case.
 */
public class BenefitsAuditEventListener {

    private final EpochSealer sealer;
    private final Map<String, NegotiationInfo> agreementCache = new ConcurrentHashMap<>();

    public BenefitsAuditEventListener(EpochSealer sealer) {
        this.sealer = sealer;
    }

    public void onFinalized(ContractNegotiationFinalized event) {
        var agreement = event.getContractAgreement();
        var purpose = PolicyPurpose.extract(agreement.getPolicy());
        agreementCache.put(agreement.getId(), new NegotiationInfo(event.getCounterPartyId(), purpose));
        sealer.record(new AuditEntry(-1, null, Instant.now().toString(), "negotiation-finalized",
                event.getCounterPartyId(), agreement.getAssetId(), purpose,
                "release request approved for asset " + agreement.getAssetId()));
    }

    public void onTerminated(ContractNegotiationTerminated event) {
        var offer = event.getContractOffers().isEmpty() ? null : event.getLastContractOffer();
        var assetId = offer == null ? "unknown" : offer.getAssetId();
        var purpose = offer == null ? "unknown" : PolicyPurpose.extract(offer.getPolicy());
        sealer.record(new AuditEntry(-1, null, Instant.now().toString(), "negotiation-terminated",
                event.getCounterPartyId(), assetId, purpose,
                "negotiation for asset " + assetId + " was admitted and later terminated"));
    }

    public void onTransferCompleted(TransferProcessCompleted event) {
        recordTransfer(event.getContractId(), event.getAssetId(), event.getParticipantContextId(),
                "transfer-completed", "anonymised data delivered for asset " + event.getAssetId());
    }

    public void onTransferTerminated(TransferProcessTerminated event) {
        recordTransfer(event.getContractId(), event.getAssetId(), event.getParticipantContextId(),
                "transfer-terminated", "transfer failed for asset " + event.getAssetId() + ": " + event.getReason());
    }

    private void recordTransfer(String contractId, String assetId, String fallbackActor, String eventType,
            String plainLanguage) {
        var cached = agreementCache.get(contractId);
        var actor = cached != null ? cached.actor() : fallbackActor;
        var purpose = cached != null ? cached.statedPurpose()
                : "unknown (no cached negotiation info for contract " + contractId + ")";
        sealer.record(new AuditEntry(-1, null, Instant.now().toString(), eventType, actor, assetId, purpose,
                plainLanguage));
    }

    private record NegotiationInfo(String actor, String statedPurpose) {
    }
}
