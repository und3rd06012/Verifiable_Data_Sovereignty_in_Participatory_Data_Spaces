package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A participant is not a dataspace-protocol consumer, so this is a plain read-only
 * resource registered directly against EDC's WebService, not a data plane transfer
 * (spec section 7). Returning only entries whose actor equals the requested
 * participantRef is the whole privacy boundary this endpoint provides: a participant
 * sees their own entries and the full public root chain, never anyone else's entries.
 */
@Path("/{participantRef}")
public class AuditResource {

    private final AuditStore store;

    public AuditResource(AuditStore store) {
        this.store = store;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AuditResponse forParticipant(@PathParam("participantRef") String participantRef) {
        var entries = store.entriesFor(participantRef);
        var epochs = store.epochChain();

        Map<String, List<String>> leavesByEpoch = new HashMap<>();
        for (var epoch : epochs) {
            // Every sealed entry's leaf position within its own epoch is needed to build
            // that entry's proof; recompute each epoch's full leaf list from every entry
            // sealed under it, in seq order, matching exactly how EpochSealer built it.
            leavesByEpoch.put(epoch.epochId(), leafHashesFor(epoch.epochId()));
        }

        Map<Long, MerkleProof> proofs = new HashMap<>();
        for (var entry : entries) {
            if (entry.epoch() == null) {
                continue;
            }
            var leaves = leavesByEpoch.get(entry.epoch());
            var index = indexOfSeq(entry.epoch(), entry.seq());
            proofs.put(entry.seq(), MerkleTree.proof(leaves, index));
        }

        return new AuditResponse(entries, proofs, epochs);
    }

    private List<String> leafHashesFor(String epochId) {
        return store.entriesInEpoch(epochId).stream().map(AuditHashing::leafHash).toList();
    }

    private int indexOfSeq(String epochId, long seq) {
        var members = store.entriesInEpoch(epochId);
        for (var i = 0; i < members.size(); i++) {
            if (members.get(i).seq() == seq) {
                return i;
            }
        }
        throw new IllegalStateException("seq " + seq + " not found among members of epoch " + epochId);
    }

    public record AuditResponse(List<AuditEntry> entries, Map<Long, MerkleProof> proofs, List<Epoch> epochs) {
    }
}
