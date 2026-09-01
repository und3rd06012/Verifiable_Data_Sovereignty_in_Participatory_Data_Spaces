package org.benefits.audit;

import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.spi.monitor.Monitor;

/**
 * Gate-0 probe only. Logs the class name and the calling thread of every event it
 * receives, so a real transfer through the running connector reveals which EDC event
 * types actually fire, in what order, and whether EventRouter dispatch is single- or
 * multi-threaded per subscriber. Deleted once BenefitsAuditEventListener (Task 6)
 * replaces it.
 */
public class ProbeEventSubscriber implements EventSubscriber {

    private final Monitor monitor;

    public ProbeEventSubscriber(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public <E extends Event> void on(EventEnvelope<E> envelope) {
        monitor.info("AUDIT_PROBE event=" + envelope.getPayload().getClass().getName()
                + " thread=" + Thread.currentThread().getName());
    }
}
