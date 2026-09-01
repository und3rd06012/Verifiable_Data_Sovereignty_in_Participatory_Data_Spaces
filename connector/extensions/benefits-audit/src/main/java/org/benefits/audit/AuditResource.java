package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/")
class AuditResource {
    AuditResource(AuditStore store) {
    }
}
