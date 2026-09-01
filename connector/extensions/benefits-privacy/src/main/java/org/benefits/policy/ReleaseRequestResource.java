package org.benefits.policy;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

/**
 * Computes and stores an anonymised release artifact at request-creation time, per
 * {@link BenefitsAggregateDataSourceFactory}'s own documented architecture: the
 * artifact is produced once here, never recomputed on transfer.
 */
@Path("/")
public class ReleaseRequestResource {

    private final ContributionSource contributions;
    private final ReleaseRequestStore requestStore;
    private final ReleaseArtifactStore artifactStore;
    private final BudgetAccountant budgetAccountant;
    private final NoiseSource noiseSource;

    public ReleaseRequestResource(ContributionSource contributions, ReleaseRequestStore requestStore,
                                   ReleaseArtifactStore artifactStore, BudgetAccountant budgetAccountant,
                                   NoiseSource noiseSource) {
        this.contributions = contributions;
        this.requestStore = requestStore;
        this.artifactStore = artifactStore;
        this.budgetAccountant = budgetAccountant;
        this.noiseSource = noiseSource;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateReleaseRequest body) {
        if (body.releaseRequestId() == null || body.releaseRequestId().isBlank()) {
            return Response.status(400).entity(new ErrorBody("releaseRequestId is required")).build();
        }
        var pipeline = new PrivacyPipeline(contributions, noiseSource, budgetAccountant);
        var query = new QuerySpec(body.siteId(), body.fields());
        var artifact = pipeline.compute(body.releaseRequestId(), query, body.k(), body.epsilon());
        if (artifact.isEmpty()) {
            return Response.status(409).entity(new ErrorBody("insufficient-budget")).build();
        }
        artifactStore.save(artifact.get());
        requestStore.save(new ReleaseRequest(body.releaseRequestId(), body.purpose(), body.recipientClass(),
                body.fields(), body.siteId(), Instant.now()));
        return Response.status(201).entity(new Created(body.releaseRequestId())).build();
    }

    public record CreateReleaseRequest(String releaseRequestId, String purpose, String recipientClass,
                                        List<String> fields, String siteId, int k, double epsilon) {
    }

    public record Created(String releaseRequestId) {
    }

    public record ErrorBody(String error) {
    }
}
