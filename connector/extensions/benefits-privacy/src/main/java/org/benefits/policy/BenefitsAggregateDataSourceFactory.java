package org.benefits.policy;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;

/**
 * Serves a previously computed anonymised artifact.
 * <p>
 * This factory NEVER computes. The artifact is produced once when the release request
 * is created and stored, so every transfer of the same release returns byte-identical
 * output. Computing here instead would let a requester re-run the transfer repeatedly
 * and average the results to recover the true value, while the privacy budget was
 * charged only once.
 * <p>
 * Because this is the only source registered for the BenefitsAggregate type, raw
 * contributions cannot leave through the release path.
 */
public class BenefitsAggregateDataSourceFactory implements DataSourceFactory {

    public static final String TYPE = "BenefitsAggregate";
    public static final String RELEASE_REQUEST_PROPERTY = "releaseRequestId";

    private final ReleaseArtifactStore store;

    public BenefitsAggregateDataSourceFactory(ReleaseArtifactStore store) {
        this.store = store;
    }

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        var artifact = artifactFor(request);
        return new InputStreamDataSource("benefits-aggregate",
                new ByteArrayInputStream(artifact.bytes()));
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        var releaseRequestId = releaseRequestId(request);
        if (releaseRequestId == null || releaseRequestId.isBlank()) {
            return Result.failure("source data address carries no " + RELEASE_REQUEST_PROPERTY);
        }
        if (store.findByReleaseRequestId(releaseRequestId) == null) {
            return Result.failure("no release artifact for release request " + releaseRequestId);
        }
        return Result.success();
    }

    private String releaseRequestId(DataFlowStartMessage request) {
        return request.getSourceDataAddress().getStringProperty(RELEASE_REQUEST_PROPERTY);
    }

    private ReleaseArtifact artifactFor(DataFlowStartMessage request) {
        var artifact = store.findByReleaseRequestId(releaseRequestId(request));
        if (artifact == null) {
            throw new IllegalStateException("no release artifact for " + releaseRequestId(request));
        }
        return artifact;
    }
}
