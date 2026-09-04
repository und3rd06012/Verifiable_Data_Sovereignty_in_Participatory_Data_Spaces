package org.benefits.policy;

/**
 * Resolves a caller-supplied Authorization header to the authenticated steward's
 * username, or null if the header is missing, CouchDB rejects it, or the
 * authenticated user's roles do not include "steward", a provisioned
 * participant's own valid credentials must not pass this check.
 */
public interface StewardAuthenticator {

    String authenticateSteward(String authorizationHeader);
}
