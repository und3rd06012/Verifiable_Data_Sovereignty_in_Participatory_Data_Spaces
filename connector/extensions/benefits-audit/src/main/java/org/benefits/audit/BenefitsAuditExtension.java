package org.benefits.audit;

import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

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
        eventRouter.register(org.eclipse.edc.spi.event.Event.class, new ProbeEventSubscriber(monitor));

        // No existing extension registers a PortMapping for a brand-new context name like
        // "audit" (unlike "management"/"protocol"/etc., each of which has its own
        // configuration extension doing this) — JettyService.registerServlet fails with
        // "No PortMapping for contextName 'audit' found" without this explicit call.
        int port = context.getSetting("web.http.audit.port", 19197);
        String path = context.getSetting("web.http.audit.path", "/audit");
        portMappingRegistry.register(new PortMapping("audit", port, path));
        webService.registerResource("audit", new ProbeAuditResource());
        monitor.info("BENEFITS audit chain: gate 0 probes registered");
    }
}
