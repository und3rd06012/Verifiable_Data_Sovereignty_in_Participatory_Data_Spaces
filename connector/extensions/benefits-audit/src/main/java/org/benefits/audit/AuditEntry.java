package org.benefits.audit;

/**
 * epoch is null until this entry has been swept into a sealed Epoch by EpochSealer
 * (Task 4). Once sealed, epoch is baked into this entry's leaf hash (AuditHashing),
 * so a store that returns a different epoch value for an already-sealed entry than
 * the one used at seal time will make that entry's inclusion proof fail to verify.
 */
public record AuditEntry(
        long seq,
        String epoch,
        String timestamp,
        String eventType,
        String actor,
        String datasetRef,
        String statedPurpose,
        String plainLanguage) {
}
