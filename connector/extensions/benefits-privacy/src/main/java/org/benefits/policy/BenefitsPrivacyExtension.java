package org.benefits.policy;

import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

public class BenefitsPrivacyExtension implements ServiceExtension {

    @Inject
    private PipelineService pipelineService;

    @Override
    public String name() {
        return "BENEFITS privacy pipeline";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        pipelineService.registerFactory(new ProbeDataSourceFactory());
        context.getMonitor().info("BENEFITS privacy pipeline registered");
    }
}
