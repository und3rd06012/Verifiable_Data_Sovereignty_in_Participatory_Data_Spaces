package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.benefits.policy.BenefitsNamespace.FIELD_SET_KEY;
import static org.benefits.policy.BenefitsNamespace.PROBE_KEY;
import static org.benefits.policy.BenefitsNamespace.PURPOSE_KEY;
import static org.benefits.policy.BenefitsNamespace.RECIPIENT_CLASS_KEY;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;
import static org.benefits.policy.BenefitsNamespace.STEWARD_QUORUM_KEY;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.policy.engine.spi.PolicyEngine.ALL_SCOPES;

public class BenefitsPolicyExtension implements ServiceExtension {

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;
    @Inject
    private PolicyEngine policyEngine;

    private StewardAuthorizationStore store;

    @Override
    public String name() {
        return "BENEFITS policy functions";
    }

    @Provider
    public StewardAuthorizationStore stewardAuthorizationStore() {
        return store;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        store = buildStore(context);

        ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, ALL_SCOPES);
        for (var key : new String[]{PROBE_KEY, RELEASE_REQUEST_KEY, STEWARD_QUORUM_KEY, PURPOSE_KEY, RECIPIENT_CLASS_KEY, FIELD_SET_KEY}) {
            ruleBindingRegistry.bind(key, NEGOTIATION_SCOPE);
        }

        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PROBE_KEY,
                new ProbeConstraintFunction(monitor));
        // The release request id is an identifier that StewardQuorumFunction reads off the rule as a
        // sibling constraint, not an assertion about the world, so there is nothing for it to decide.
        // It still needs a registered function because EDC rejects a bound left operand that has none.
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, RELEASE_REQUEST_KEY,
                (operator, rightValue, rule, policyContext) -> true);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, STEWARD_QUORUM_KEY,
                new StewardQuorumFunction(store, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PURPOSE_KEY,
                new PurposeFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, RECIPIENT_CLASS_KEY,
                new RecipientClassFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, FIELD_SET_KEY,
                new FieldSetFunction());

        monitor.info("BENEFITS policy functions registered");

        seed(context);
    }

    private StewardAuthorizationStore buildStore(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.steward.persistence.enabled", "false"))) {
            return new InMemoryStewardAuthorizationStore();
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-steward";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbStewardAuthorizationStore(url, database, user, password,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void seed(ServiceExtensionContext context) {
        var path = context.getSetting("benefits.seed.authorizations", "");
        if (path.isBlank()) {
            return;
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        try {
            var type = mapper.getTypeFactory().constructCollectionType(java.util.List.class, StewardAuthorization.class);
            java.util.List<StewardAuthorization> authorizations =
                    mapper.readValue(java.nio.file.Files.readString(java.nio.file.Path.of(path)), type);
            authorizations.forEach(store::save);
            context.getMonitor().info("seeded " + authorizations.size() + " steward authorizations from " + path);
        } catch (java.io.IOException e) {
            throw new org.eclipse.edc.spi.EdcException("failed to seed steward authorizations from " + path, e);
        }
    }
}
