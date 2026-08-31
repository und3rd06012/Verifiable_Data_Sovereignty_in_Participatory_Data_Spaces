# EDC 0.18.0: how a custom policy leftOperand expands

Verified on 2026-08-30 against EDC 0.18.0 with a live provider/consumer contract
negotiation driven by `tools/negotiate.sh`.

## The form that works

Declare the BENEFITS prefix in the `@context` of the ODRL `policy` node itself,
alongside the ODRL context, and use a compact IRI for `leftOperand`:

```json
"policy": {
  "@context": [
    "http://www.w3.org/ns/odrl.jsonld",
    { "benefits": "https://w3id.org/benefits/v1/ns/" }
  ],
  "@type": "Set",
  "permission": [
    {
      "action": "use",
      "constraint": {
        "@type": "AtomicConstraint",
        "leftOperand": "benefits:probe",
        "operator": "eq",
        "rightOperand": "on"
      }
    }
  ]
}
```

This is the form the brief specified, and it worked on the first attempt. No
fallback to `EDC_NAMESPACE + "probe"` with a bare `"leftOperand": "probe"` was
needed, and that fallback was never exercised.

The matching Java registration is the fully qualified IRI:

```java
public static final String PROBE_KEY = "https://w3id.org/benefits/v1/ns/" + "probe";

ruleBindingRegistry.bind(PROBE_KEY, ContractNegotiationPolicyContext.NEGOTIATION_SCOPE);
policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
        PROBE_KEY, new ProbeConstraintFunction(monitor));
```

## Why it works

`document/odrl.jsonld` is bundled inside the connector shadow jar (EDC caches the
remote ODRL context locally, so no network fetch happens at runtime). In that
context `leftOperand` is typed as `@vocab`:

```json
"leftOperand": { "@type": "@vocab", "@id": "odrl:leftOperand" }
```

A `@vocab`-typed value is expanded as an IRI, and a compact IRI whose prefix is
defined in the active context wins over the document `@vocab`. So `benefits:probe`
expands to `https://w3id.org/benefits/v1/ns/probe`, which is exactly the key the
policy engine function is registered under. A bare `"leftOperand": "probe"` would
instead pick up the surrounding `@vocab` (the EDC namespace) and produce
`https://w3id.org/edc/v0.0.1/ns/probe`, which would not match.

A nested `@context` on the `policy` node is legal JSON-LD (a scoped context on a
node object), so the outer management-API context stays in force for the
surrounding EDC properties while the ODRL terms resolve inside the policy.

## How to confirm the expansion without running a negotiation

Create the policy definition, then read it back from the Management API. The
stored, expanded left operand is visible directly:

```
curl -s -H "x-api-key: password" \
     http://localhost:19193/management/v3/policydefinitions/probe-policy
```

The response contains:

```json
"odrl:constraint": {
  "odrl:leftOperand": { "@id": "https://w3id.org/benefits/v1/ns/probe" },
  "odrl:operator": { "@id": "odrl:eq" },
  "odrl:rightOperand": "on"
}
```

If that `@id` is anything other than the BENEFITS IRI, the constraint function will
never be invoked even though the negotiation may still finalize, because an unbound
left operand is filtered out of the policy before evaluation rather than failing it.
This one request is the fastest discriminator between an expansion problem and a
registration problem.

## Where the function runs

The provider evaluates the contract policy in the `contract.negotiation` scope, so
the `BENEFITS_PROBE_EVALUATED` marker appears in the provider log, once per
negotiation. It does not appear in the consumer log.
