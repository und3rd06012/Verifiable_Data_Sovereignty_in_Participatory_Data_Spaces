package org.benefits.policy;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class ProbeDataSourceFactory implements DataSourceFactory {

    public static final String TYPE = "BenefitsProbe";
    public static final String MARKER = "BENEFITS_TRANSFER_OK";

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        var body = MARKER + " asset=" + request.getAssetId();
        return new InputStreamDataSource("probe",
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        return Result.success();
    }
}
