package org.benefits.audit;

/**
 * Resolves a caller-supplied Authorization header to the identity CouchDB itself
 * authenticated, or null if the header is missing, malformed, or CouchDB rejects it.
 */
public interface SessionAuthenticator {

    String authenticate(String authorizationHeader);
}
