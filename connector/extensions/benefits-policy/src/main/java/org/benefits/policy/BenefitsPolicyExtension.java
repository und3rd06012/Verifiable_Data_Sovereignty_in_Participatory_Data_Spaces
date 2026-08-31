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

    private final StewardAuthorizationStore store = new InMemoryStewardAuthorizationStore();

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

        ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, ALL_SCOPES);
        for (var key : new String[]{PROBE_KEY, RELEASE_REQUEST_KEY, STEWARD_QUORUM_KEY, PURPOSE_KEY, RECIPIENT_CLASS_KEY, FIELD_SET_KEY}) {
            ruleBindingRegistry.bind(key, NEGOTIATION_SCOPE);
        }

        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PROBE_KEY,
                new ProbeConstraintFunction(monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, STEWARD_QUORUM_KEY,
                new StewardQuorumFunction(store, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PURPOSE_KEY,
                new PurposeFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, RECIPIENT_CLASS_KEY,
                new RecipientClassFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, FIELD_SET_KEY,
                new FieldSetFunction());

        monitor.info("BENEFITS policy functions registered");
    }
}
