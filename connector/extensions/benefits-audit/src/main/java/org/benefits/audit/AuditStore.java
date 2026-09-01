package org.benefits.audit;

import java.util.List;

/**
 * Separates Merkle sealing logic from persistence, the same seam ContributionSource
 * gave benefits-privacy in phase 3: tests run the sealing logic against
 * InMemoryAuditStore, with no database, and only CouchDbAuditStore (Task 5) needs an
 * integration test.
 * <p>
 * sealEpoch and incompleteEpochs together give crash-safe resume: sealEpoch persists
 * the Epoch document (with its member seq range recorded internally, beyond the four
 * fields Epoch itself exposes) and then updates every member entry's epoch field.
 * If the process crashes between those two steps, incompleteEpochs finds the
 * already-written Epoch whose member entries are not all updated yet, and the next
 * startup resumes at the update step with the exact same seq range rather than
 * recomputing a root that a newly arrived entry could have changed.
 */
public interface AuditStore {

    void append(AuditEntry entry);

    long nextSeq();

    List<AuditEntry> unsealedEntries();

    void sealEpoch(Epoch epoch, long firstSeq, long lastSeq);

    List<UnsealedRange> incompleteEpochs();

    List<AuditEntry> entriesFor(String participantRef);

    List<Epoch> epochChain();

    record UnsealedRange(Epoch epoch, long firstSeq, long lastSeq) {
    }
}
