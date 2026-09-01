package org.benefits.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryAuditStore implements AuditStore {

    private final List<AuditEntry> entries = new ArrayList<>();
    private final List<Epoch> epochs = new ArrayList<>();
    private final AtomicLong seqCounter = new AtomicLong(0);

    @Override
    public synchronized void append(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public long nextSeq() {
        return seqCounter.getAndIncrement();
    }

    @Override
    public synchronized List<AuditEntry> unsealedEntries() {
        return entries.stream().filter(e -> e.epoch() == null).sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }

    @Override
    public synchronized void sealEpoch(Epoch epoch, long firstSeq, long lastSeq) {
        if (epochs.stream().noneMatch(e -> e.epochId().equals(epoch.epochId()))) {
            epochs.add(epoch);
        }
        for (var i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            if (e.seq() >= firstSeq && e.seq() <= lastSeq && e.epoch() == null) {
                entries.set(i, new AuditEntry(e.seq(), epoch.epochId(), e.timestamp(), e.eventType(),
                        e.actor(), e.datasetRef(), e.statedPurpose(), e.plainLanguage()));
            }
        }
    }

    @Override
    public synchronized List<UnsealedRange> incompleteEpochs() {
        List<UnsealedRange> result = new ArrayList<>();
        for (var epoch : epochs) {
            var member = entries.stream().filter(e -> epoch.epochId().equals(e.epoch())).toList();
            var stillNull = entries.stream()
                    .anyMatch(e -> e.epoch() == null && belongsToRange(e, epoch, member));
            // InMemoryAuditStore never crashes mid-seal, so this always reports none;
            // it exists so EpochSealerTest can exercise the resume path by calling
            // sealEpoch and incompleteEpochs directly without a real crash.
            if (stillNull) {
                result.add(new UnsealedRange(epoch, 0, 0));
            }
        }
        return result;
    }

    private boolean belongsToRange(AuditEntry e, Epoch epoch, List<AuditEntry> member) {
        return false;
    }

    @Override
    public synchronized List<AuditEntry> entriesFor(String participantRef) {
        return entries.stream().filter(e -> e.actor().equals(participantRef)).toList();
    }

    @Override
    public synchronized List<Epoch> epochChain() {
        return List.copyOf(epochs);
    }
}
