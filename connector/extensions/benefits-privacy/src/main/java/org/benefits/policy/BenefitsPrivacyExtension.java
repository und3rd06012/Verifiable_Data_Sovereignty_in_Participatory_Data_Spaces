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
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

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
    @Inject
    private WebService webService;
    @Inject
    private PortMappingRegistry portMappingRegistry;
    @Inject
    private StewardAuthorizationStore stewardAuthorizationStore;

    private ReleaseArtifactStore artifactStore;

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
        artifactStore = buildArtifactStore(context);
        pipelineService.registerFactory(new ProbeDataSourceFactory());
        pipelineService.registerFactory(new BenefitsAggregateDataSourceFactory(artifactStore));

        ruleBindingRegistry.bind(K_ANONYMITY_KEY, NEGOTIATION_SCOPE);
        ruleBindingRegistry.bind(EPSILON_BUDGET_KEY, NEGOTIATION_SCOPE);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                K_ANONYMITY_KEY, new KAnonymityFunction(artifactStore, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                EPSILON_BUDGET_KEY, new EpsilonBudgetFunction(artifactStore, monitor));

        monitor.info("BENEFITS privacy pipeline registered");

        if (Boolean.parseBoolean(context.getSetting("benefits.releaseRequests.enabled", "false"))) {
            var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
            var database = context.getSetting("benefits.couchdb.database", "benefits");
            var user = context.getSetting("benefits.couchdb.user", "admin");
            var password = context.getSetting("benefits.couchdb.password", "");
            var contributions = new CouchDbContributionSource(url, database, user, password,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var releaseRequestStore = buildReleaseRequestStore(context);
            var resource = new ReleaseRequestResource(contributions, releaseRequestStore, artifactStore,
                    buildBudgetAccountant(context), new LaplaceNoiseSource(new java.security.SecureRandom()));

            var port = context.getSetting("web.http.releaseRequests.port", 19198);
            var path = context.getSetting("web.http.releaseRequests.path", "/release-requests");
            portMappingRegistry.register(new PortMapping("releaseRequests", port, path));
            webService.registerResource("releaseRequests", resource);
        }

        if (Boolean.parseBoolean(context.getSetting("benefits.stewardApi.enabled", "false"))) {
            var couchdbUrl = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
            var couchUser = context.getSetting("benefits.couchdb.user", "admin");
            var couchPassword = context.getSetting("benefits.couchdb.password", "");
            var stewardAuthenticator = new CouchDbStewardAuthenticator(couchdbUrl, new com.fasterxml.jackson.databind.ObjectMapper());
            var provisioner = new CouchDbUserProvisioner(couchdbUrl, couchUser, couchPassword,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var stewardResource = new StewardResource(stewardAuthorizationStore, buildReleaseRequestStore(context),
                    stewardAuthenticator, provisioner);

            var stewardPort = context.getSetting("web.http.steward.port", 19199);
            var stewardPath = context.getSetting("web.http.steward.path", "/steward");
            portMappingRegistry.register(new PortMapping("steward", stewardPort, stewardPath));
            webService.registerResource("steward", stewardResource);
        }

        seed(context);
    }

    private ReleaseRequestStore buildReleaseRequestStore(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.privacy.persistence.enabled", "false"))) {
            return new InMemoryReleaseRequestStore();
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-requests";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbReleaseRequestStore(url, database, user, password, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private ReleaseArtifactStore buildArtifactStore(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.privacy.persistence.enabled", "false"))) {
            return new InMemoryReleaseArtifactStore();
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-artifacts";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbReleaseArtifactStore(url, database, user, password, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    BudgetAccountant buildBudgetAccountant(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.privacy.persistence.enabled", "false"))) {
            return new InMemoryBudgetAccountant(Double.parseDouble(context.getSetting("benefits.privacy.budget.total", "1.0")));
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-budget";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        var total = Double.parseDouble(context.getSetting("benefits.privacy.budget.total", "1.0"));
        return new CouchDbBudgetAccountant(url, database, user, password, new com.fasterxml.jackson.databind.ObjectMapper(), total);
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
