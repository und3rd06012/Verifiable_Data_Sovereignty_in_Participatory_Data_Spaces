package org.benefits.audit;

import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationTerminated;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessTerminated;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

public class BenefitsAuditExtension implements ServiceExtension {

    @Inject
    private EventRouter eventRouter;
    @Inject
    private WebService webService;
    @Inject
    private PortMappingRegistry portMappingRegistry;

    @Override
    public String name() {
        return "BENEFITS audit chain";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        if (!Boolean.parseBoolean(context.getSetting("benefits.audit.enabled", "false"))) {
            monitor.info("BENEFITS audit chain disabled (benefits.audit.enabled not set) — skipping");
            return;
        }
        var epochSize = Integer.parseInt(context.getSetting("benefits.audit.epochSize", "50"));
        var store = buildStore(context);
        var sealer = new EpochSealer(store, epochSize);
        var listener = new BenefitsAuditEventListener(sealer);

        eventRouter.register(ContractNegotiationFinalized.class, envelopeSubscriber(listener::onFinalized));
        eventRouter.register(ContractNegotiationTerminated.class, envelopeSubscriber(listener::onTerminated));
        eventRouter.register(TransferProcessCompleted.class, envelopeSubscriber(listener::onTransferCompleted));
        eventRouter.register(TransferProcessTerminated.class, envelopeSubscriber(listener::onTransferTerminated));

        // Task 1's gate 0 probe found this is required: JettyExtension only
        // auto-registers the default (unnamed) web.http.port context. Every other
        // named context (management, protocol, ...) has its own configuration
        // extension elsewhere in the EDC BOM that does this same call before
        // registerResource; "audit" is a brand-new context name with no such
        // extension, so this extension must register its own PortMapping or the
        // provider fails to boot with "No PortMapping for contextName 'audit' found".
        var port = context.getSetting("web.http.audit.port", 19197);
        var path = context.getSetting("web.http.audit.path", "/audit");
        portMappingRegistry.register(new PortMapping("audit", port, path));
        var couchdbUrl = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var authenticator = new CouchDbSessionAuthenticator(couchdbUrl, new ObjectMapper());
        webService.registerResource("audit", new AuditResource(store, authenticator));
        monitor.info("BENEFITS audit chain registered, epochSize=" + epochSize);
    }

    private AuditStore buildStore(ServiceExtensionContext context) {
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-audit";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbAuditStore(url, database, user, password, new ObjectMapper());
    }

    private <T extends Event> EventSubscriber envelopeSubscriber(java.util.function.Consumer<T> handler) {
        // EventSubscriber.on is confirmed (Task 1's gate 0 report) to be a generic
        // method — <E extends Event> void on(EventEnvelope<E>) — not a method taking
        // a wildcarded parameter. A lambda expression cannot implement an interface
        // method that declares its own type parameter (there is no lambda syntax for
        // <E>), so this must be an anonymous class, not `envelope -> ...`.
        return new EventSubscriber() {
            @Override
            @SuppressWarnings("unchecked")
            public <E extends Event> void on(EventEnvelope<E> envelope) {
                handler.accept((T) envelope.getPayload());
            }
        };
    }
}
