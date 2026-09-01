package org.benefits.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The sole writer of AuditStore.append and AuditStore.sealEpoch. Whether EDC's
 * EventRouter dispatches to one subscriber on a single thread is confirmed by Task
 * 1's gate 0 probe; this lock makes correctness independent of that answer, exactly
 * as section 4.2 of the spec requires.
 */
public class EpochSealer {

    private final AuditStore store;
    private final int epochSize;
    private final ReentrantLock lock = new ReentrantLock();

    public EpochSealer(AuditStore store, int epochSize) {
        this.store = store;
        this.epochSize = epochSize;
        resumeIncompleteSeals();
    }

    public AuditEntry record(AuditEntry entryWithoutSeq) {
        lock.lock();
        try {
            var seq = store.nextSeq();
            var stamped = new AuditEntry(seq, null, entryWithoutSeq.timestamp(), entryWithoutSeq.eventType(),
                    entryWithoutSeq.actor(), entryWithoutSeq.datasetRef(), entryWithoutSeq.statedPurpose(),
                    entryWithoutSeq.plainLanguage());
            store.append(stamped);
            sealIfThresholdCrossed();
            return stamped;
        } finally {
            lock.unlock();
        }
    }

    private void sealIfThresholdCrossed() {
        var unsealed = store.unsealedEntries();
        if (unsealed.size() < epochSize) {
            return;
        }
        var toSeal = unsealed.subList(0, epochSize);
        var prior = store.epochChain();
        var prevHash = prior.isEmpty()
                ? AuditHashing.GENESIS_PREV_EPOCH_HASH
                : AuditHashing.prevEpochHash(prior.get(prior.size() - 1).epochId(),
                        prior.get(prior.size() - 1).merkleRoot(), prior.get(prior.size() - 1).sealedAt());
        var sealedAt = Instant.now().toString();
        var epochId = UUID.randomUUID().toString();

        // Leaf hashes must be computed as entries will read back AFTER sealing (epoch
        // field set to epochId), not as they sit right now (epoch field still null).
        // AuditResource later rebuilds these same leaf hashes from the persisted,
        // epoch-stamped entries to construct a proof against this root; hashing the
        // unstamped entries here would bake a different epoch value into the root
        // than AuditResource's later leaf hashes carry, and every proof would fail.
        var stampedForHashing = toSeal.stream()
                .map(e -> new AuditEntry(e.seq(), epochId, e.timestamp(), e.eventType(), e.actor(),
                        e.datasetRef(), e.statedPurpose(), e.plainLanguage()))
                .toList();
        var leafHashes = stampedForHashing.stream().map(AuditHashing::leafHash).toList();
        var root = MerkleTree.root(leafHashes);

        var epoch = new Epoch(epochId, root, prevHash, sealedAt);
        store.sealEpoch(epoch, toSeal.get(0).seq(), toSeal.get(toSeal.size() - 1).seq());
    }

    private void resumeIncompleteSeals() {
        lock.lock();
        try {
            for (var incomplete : store.incompleteEpochs()) {
                store.sealEpoch(incomplete.epoch(), incomplete.firstSeq(), incomplete.lastSeq());
            }
        } finally {
            lock.unlock();
        }
    }

    public List<AuditEntry> entriesForAsset(String datasetRef) {
        lock.lock();
        try {
            return store.entriesForAsset(datasetRef);
        } finally {
            lock.unlock();
        }
    }
}
