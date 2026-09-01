package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/probe")
public class ProbeAuditResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String probe() {
        return "AUDIT_PROBE_OK";
    }
}
