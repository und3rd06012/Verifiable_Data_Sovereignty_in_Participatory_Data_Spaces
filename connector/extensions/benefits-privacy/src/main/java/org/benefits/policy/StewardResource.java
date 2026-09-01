package org.benefits.policy;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.spi.EdcException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Path("/")
public class StewardResource {

    // Tightened to a safe subset of CouchDB's own database-name rules
    // (^[a-z][a-z0-9_$()+/-]*$) now that provisioning always creates a
    // "participant-<ref>" database -- a ref that fails this check could
    // never be provisioned successfully.
    private static final Pattern SAFE_PARTICIPANT_REF = Pattern.compile("^[a-z][a-z0-9_-]*$");

    private final StewardAuthorizationStore authorizationStore;
    private final ReleaseRequestStore requestStore;
    private final StewardAuthenticator authenticator;
    private final CouchDbUserProvisioner userProvisioner;
    private final CouchDbParticipantDatabaseProvisioner databaseProvisioner;
    private final CouchDbReplicationProvisioner replicationProvisioner;

    public StewardResource(StewardAuthorizationStore authorizationStore, ReleaseRequestStore requestStore,
                            StewardAuthenticator authenticator, CouchDbUserProvisioner userProvisioner,
                            CouchDbParticipantDatabaseProvisioner databaseProvisioner,
                            CouchDbReplicationProvisioner replicationProvisioner) {
        this.authorizationStore = authorizationStore;
        this.requestStore = requestStore;
        this.authenticator = authenticator;
        this.userProvisioner = userProvisioner;
        this.databaseProvisioner = databaseProvisioner;
        this.replicationProvisioner = replicationProvisioner;
    }

    @GET
    @Path("/queue")
    @Produces(MediaType.APPLICATION_JSON)
    public Response queue(@HeaderParam("Authorization") String authorizationHeader) {
        if (authenticator.authenticateSteward(authorizationHeader) == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            var entries = requestStore.all().stream()
                    .map(r -> new QueueEntry(r, authorizationStore.findByReleaseRequestId(r.releaseRequestId())))
                    .toList();
            return Response.ok(entries).build();
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{requestId}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@PathParam("requestId") String requestId,
                            @HeaderParam("Authorization") String authorizationHeader, ReviewBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (body == null) {
            return Response.status(400).entity(new ErrorBody("request body is required")).build();
        }
        try {
            authorizationStore.appendReview(requestId, body.datasetHash(),
                    new StewardReview(steward, body.rubricScore(), body.notes(), Instant.now()));
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody(e.getMessage())).build();
        }
        return Response.status(201).build();
    }

    @POST
    @Path("/{requestId}/co-sign")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response coSign(@PathParam("requestId") String requestId,
                            @HeaderParam("Authorization") String authorizationHeader, CoSignBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (body == null) {
            return Response.status(400).entity(new ErrorBody("request body is required")).build();
        }
        try {
            authorizationStore.appendSignature(requestId, body.datasetHash(),
                    new StewardSignature(steward, body.signature(), Instant.now()));
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody(e.getMessage())).build();
        }
        return Response.status(201).build();
    }

    @POST
    @Path("/{requestId}/veto")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response veto(@PathParam("requestId") String requestId,
                          @HeaderParam("Authorization") String authorizationHeader, VetoBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (body == null) {
            return Response.status(400).entity(new ErrorBody("request body is required")).build();
        }
        try {
            authorizationStore.appendVeto(requestId, body.datasetHash(),
                    new StewardVeto(steward, body.reason(), Instant.now()));
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody(e.getMessage())).build();
        }
        return Response.status(201).build();
    }

    @POST
    @Path("/{requestId}/appeal")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response appeal(@PathParam("requestId") String requestId,
                            @HeaderParam("Authorization") String authorizationHeader, AppealBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (body == null) {
            return Response.status(400).entity(new ErrorBody("request body is required")).build();
        }
        try {
            authorizationStore.appendAppeal(requestId, new StewardAppeal(steward, body.reason(), body.vetoStewardId(), Instant.now()));
        } catch (EdcException e) {
            return Response.status(409).entity(new ErrorBody(e.getMessage())).build();
        }
        return Response.status(201).build();
    }

    @POST
    @Path("/participants/{participantRef}/provision")
    @Produces(MediaType.APPLICATION_JSON)
    public Response provisionParticipant(@PathParam("participantRef") String participantRef,
                                          @HeaderParam("Authorization") String authorizationHeader) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (!SAFE_PARTICIPANT_REF.matcher(participantRef).matches()) {
            return Response.status(400).entity(new ErrorBody(
                    "participantRef must start with a lowercase letter and contain only lowercase letters, digits, '-', or '_'")).build();
        }
        // Database/security/design-doc/replication first, the CouchDB user (and its
        // freshly generated password) last. CouchDB's _security document accepts a
        // username that doesn't exist yet -- it's just a string checked against the
        // authenticated caller at request time -- so this ordering is safe, and it means
        // a failure before the user is created leaves nothing irrecoverable to retry: no
        // password was ever generated, so a retry simply starts over. The reverse order
        // would risk creating the user (with a password only this response would ever
        // carry), then losing that password forever if the database step failed after --
        // a retry's re-PUT to the same _users document would 409 and change nothing, per
        // CouchDbUserProvisioner's own documented behavior.
        try {
            databaseProvisioner.provision(participantRef);
            replicationProvisioner.ensureReplication(databaseProvisioner.databaseNameFor(participantRef), "benefits");
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody("failed to provision participant database: " + e.getMessage())).build();
        }
        var password = generatePassword();
        CouchDbUserProvisioner.Outcome userOutcome;
        try {
            userOutcome = userProvisioner.provision(participantRef, password, List.of());
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody(
                    "participant database provisioned but CouchDB user creation failed: " + e.getMessage())).build();
        }
        if (userOutcome == CouchDbUserProvisioner.Outcome.ALREADY_EXISTS) {
            return Response.status(200).entity(new ProvisionStatus(participantRef,
                    "participant is already provisioned; no new password was issued")).build();
        }
        return Response.status(201).entity(new ProvisionedCredentials(participantRef, password)).build();
    }

    private static String generatePassword() {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record QueueEntry(ReleaseRequest request, StewardAuthorization authorization) {
    }

    public record ReviewBody(String datasetHash, int rubricScore, String notes) {
    }

    public record CoSignBody(String datasetHash, String signature) {
    }

    public record VetoBody(String datasetHash, String reason) {
    }

    public record AppealBody(String reason, String vetoStewardId) {
    }

    public record ProvisionedCredentials(String participantRef, String password) {
    }

    public record ProvisionStatus(String participantRef, String message) {
    }

    public record ErrorBody(String error) {
    }
}
