package org.benefits.policy;

import org.eclipse.edc.participantcontext.spi.config.ParticipantContextConfig;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.iam.AudienceResolver;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.iam.VerificationContext;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.types.TypeManager;

import java.util.Map;

public class BenefitsIamExtension implements ServiceExtension {

    @Inject
    private ParticipantContextConfig contextConfig;
    @Inject
    private TypeManager typeManager;
    // Never read directly: this @Inject forces BenefitsIamExtension to load after EDC's mock IAM
    // so that this extension's @Provider IdentityService wins the override. Removing it would
    // silently revert to mock identity, with only a log warning to notice.
    @Inject
    private AudienceResolver audienceResolver;
    @Inject
    private Monitor monitor;

    @Override
    public String name() {
        return "BENEFITS mock IAM claims";
    }

    @Provider
    public IdentityService identityService() {
        return new BenefitsClaimsIdentityService(contextConfig, typeManager, monitor);
    }

    private static class BenefitsClaimsIdentityService implements IdentityService {
        private final ParticipantContextConfig contextConfig;
        private final TypeManager typeManager;
        private final Monitor monitor;

        BenefitsClaimsIdentityService(ParticipantContextConfig contextConfig, TypeManager typeManager, Monitor monitor) {
            this.contextConfig = contextConfig;
            this.typeManager = typeManager;
            this.monitor = monitor;
        }

        @Override
        public Result<TokenRepresentation> obtainClientCredentials(String participantContextId, TokenParameters parameters) {
            // The requester claims are read out of the participant context identified by
            // participantContextId, so this log line records which context EDC actually passes in.
            monitor.info("obtainClientCredentials participantContextId=" + participantContextId);
            var token = Map.of(
                    "client_id", contextConfig.getString(participantContextId, "edc.participant.id"),
                    "purpose", contextConfig.getString(participantContextId, "benefits.requester.purpose", ""),
                    "recipientClass", contextConfig.getString(participantContextId, "benefits.requester.recipientClass", ""),
                    "requestedFields", contextConfig.getString(participantContextId, "benefits.requester.fields", "")
            );
            return Result.success(TokenRepresentation.Builder.newInstance()
                    .token(typeManager.writeValueAsString(token))
                    .build());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result<ClaimToken> verifyJwtToken(String participantContextId, TokenRepresentation tokenRepresentation, VerificationContext context) {
            Map<String, Object> token = typeManager.readValue(tokenRepresentation.getToken(), Map.class);
            return Result.success(ClaimToken.Builder.newInstance()
                    .claim("client_id", token.get("client_id"))
                    .claim("purpose", token.get("purpose"))
                    .claim("recipientClass", token.get("recipientClass"))
                    .claim("requestedFields", token.get("requestedFields"))
                    .build());
        }
    }
}
