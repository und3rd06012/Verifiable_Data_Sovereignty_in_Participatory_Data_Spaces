package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A participant is not a dataspace-protocol consumer, so this is a plain read-only
 * resource registered directly against EDC's WebService, not a data plane transfer
 * (spec section 7). The caller's Authorization header is validated against CouchDB's
 * own /_session endpoint (SessionAuthenticator); the authenticated identity must
 * equal the requested participantRef, or this returns 403. Filtering entries by that
 * same participantRef is the second half of the privacy boundary: even an
 * authenticated participant only ever sees their own entries and the public epoch
 * chain, never anyone else's entries.
 */
@Path("/{participantRef}")
public class AuditResource {

    private final AuditStore store;
    private final SessionAuthenticator authenticator;

    public AuditResource(AuditStore store, SessionAuthenticator authenticator) {
        this.store = store;
        this.authenticator = authenticator;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response forParticipant(@PathParam("participantRef") String participantRef,
                                    @HeaderParam("Authorization") String authorizationHeader) {
        var authenticated = authenticator.authenticate(authorizationHeader);
        if (authenticated == null || !authenticated.equals(participantRef)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        var entries = store.entriesFor(participantRef);
        var epochs = store.epochChain();

        Map<String, List<String>> leavesByEpoch = new HashMap<>();
        for (var epoch : epochs) {
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

        return Response.ok(new AuditResponse(entries, proofs, epochs)).build();
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
