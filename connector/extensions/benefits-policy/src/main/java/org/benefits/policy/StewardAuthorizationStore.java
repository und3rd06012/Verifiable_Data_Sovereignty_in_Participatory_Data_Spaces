package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

public interface StewardAuthorizationStore {

    @Nullable
    StewardAuthorization findByReleaseRequestId(String releaseRequestId);

    /**
     * Stores the given authorization, replacing any existing authorization for the
     * same release request id wholesale rather than merging into it. The caller must
     * be the sole writer for a given release request id when calling this method;
     * the only caller in this codebase is BenefitsPolicyExtension.seed(), which runs
     * once at startup, never concurrently. Live HTTP request handlers must use the
     * append* methods below instead, which are atomic per release request id.
     */
    void save(StewardAuthorization authorization);

    /**
     * Atomically appends a review, creating a fresh authorization (with the given
     * datasetHash) if none exists yet for this release request id. If an authorization
     * already exists, the given datasetHash is ignored in favour of the one already
     * stored -- datasetHash is fixed at first creation, not updated on later appends.
     */
    StewardAuthorization appendReview(String releaseRequestId, String datasetHash, StewardReview review);

    StewardAuthorization appendSignature(String releaseRequestId, String datasetHash, StewardSignature signature);

    StewardAuthorization appendVeto(String releaseRequestId, String datasetHash, StewardVeto veto);

    /**
     * Appends an appeal contesting an existing veto. Throws EdcException if no
     * authorization exists yet for this release request id, or if no veto by
     * appeal.vetoStewardId() is recorded against it -- an appeal cannot create the
     * veto it contests.
     */
    StewardAuthorization appendAppeal(String releaseRequestId, StewardAppeal appeal);
}
