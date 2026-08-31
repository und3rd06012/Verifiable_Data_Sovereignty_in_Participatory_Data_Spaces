package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.benefits.policy.BenefitsNamespace.EPSILON_BUDGET_KEY;
import static org.benefits.policy.BenefitsNamespace.K_ANONYMITY_KEY;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;

public class BenefitsPrivacyExtension implements ServiceExtension {

    @Inject
    private PipelineService pipelineService;
    @Inject
    private RuleBindingRegistry ruleBindingRegistry;
    @Inject
    private PolicyEngine policyEngine;

    private final ReleaseArtifactStore artifactStore = new InMemoryReleaseArtifactStore();

    @Override
    public String name() {
        return "BENEFITS privacy pipeline";
    }

    @Provider
    public ReleaseArtifactStore releaseArtifactStore() {
        return artifactStore;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        pipelineService.registerFactory(new ProbeDataSourceFactory());
        pipelineService.registerFactory(new BenefitsAggregateDataSourceFactory(artifactStore));

        ruleBindingRegistry.bind(K_ANONYMITY_KEY, NEGOTIATION_SCOPE);
        ruleBindingRegistry.bind(EPSILON_BUDGET_KEY, NEGOTIATION_SCOPE);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                K_ANONYMITY_KEY, new KAnonymityFunction(artifactStore, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                EPSILON_BUDGET_KEY, new EpsilonBudgetFunction(artifactStore, monitor));

        monitor.info("BENEFITS privacy pipeline registered");

        seed(context);
    }

    private void seed(ServiceExtensionContext context) {
        var path = context.getSetting("benefits.seed.artifacts", "");
        if (path.isBlank()) {
            return;
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var json = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(path)));
            for (var node : json) {
                artifactStore.save(new ReleaseArtifact(
                        node.path("releaseRequestId").asText(),
                        node.path("payload").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        node.path("computedK").asInt(),
                        node.path("epsilonCharged").asDouble()));
            }
            context.getMonitor().info("seeded release artifacts from " + path);
        } catch (java.io.IOException e) {
            throw new org.eclipse.edc.spi.EdcException("failed to seed release artifacts from " + path, e);
        }
    }
}
