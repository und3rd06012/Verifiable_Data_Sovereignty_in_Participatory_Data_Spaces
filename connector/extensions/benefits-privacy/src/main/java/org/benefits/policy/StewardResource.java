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

@Path("/")
public class StewardResource {

    private final StewardAuthorizationStore authorizationStore;
    private final ReleaseRequestStore requestStore;
    private final StewardAuthenticator authenticator;
    private final CouchDbUserProvisioner provisioner;

    public StewardResource(StewardAuthorizationStore authorizationStore, ReleaseRequestStore requestStore,
                            StewardAuthenticator authenticator, CouchDbUserProvisioner provisioner) {
        this.authorizationStore = authorizationStore;
        this.requestStore = requestStore;
        this.authenticator = authenticator;
        this.provisioner = provisioner;
    }

    @GET
    @Path("/queue")
    @Produces(MediaType.APPLICATION_JSON)
    public Response queue(@HeaderParam("Authorization") String authorizationHeader) {
        if (authenticator.authenticateSteward(authorizationHeader) == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        var entries = requestStore.all().stream()
                .map(r -> new QueueEntry(r, authorizationStore.findByReleaseRequestId(r.releaseRequestId())))
                .toList();
        return Response.ok(entries).build();
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
        authorizationStore.appendReview(requestId, body.datasetHash(),
                new StewardReview(steward, body.rubricScore(), body.notes(), Instant.now()));
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
        authorizationStore.appendSignature(requestId, body.datasetHash(),
                new StewardSignature(steward, body.signature(), Instant.now()));
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
        authorizationStore.appendVeto(requestId, body.datasetHash(),
                new StewardVeto(steward, body.reason(), Instant.now()));
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
        var password = generatePassword();
        provisioner.provision(participantRef, password, List.of());
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

    public record ErrorBody(String error) {
    }
}
