package com.yrootlab.onmaru.catalog.application.selectedsync;

import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;

import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An operator previews merged detail, then confirms its exact hashes and fingerprint. */
public final class SelectedDiscoveryApprovalService {
    private final CandidateStore store;
    private final SelectedDiscoverySync.DetailSource details;
    private final SelectedDiscoverySync.TaxonomyRegistry taxonomy;
    private final DiscoveryCandidatePolicy policy = new DiscoveryCandidatePolicy();

    public SelectedDiscoveryApprovalService(CandidateStore store, SelectedDiscoverySync.DetailSource details,
                                            SelectedDiscoverySync.TaxonomyRegistry taxonomy) {
        this.store = Objects.requireNonNull(store);
        this.details = Objects.requireNonNull(details);
        this.taxonomy = Objects.requireNonNull(taxonomy);
    }

    public Preview preview(String contentId) {
        SourceRecord list = store.latestCandidate(contentId);
        if (list == null) throw new IllegalArgumentException("Candidate not found: " + contentId);
        var fields = new HashMap<>(list.fields());
        details.fetch(list).forEach((key, value) -> {
            if (Set.of("overview", "detail", "homepage", "usetime", "restdate", "parking", "infocenter").contains(key))
                fields.put(key, value);
        });
        SourceRecord merged = new SourceRecord(list.provider(), list.operation(), fields);
        boolean known = taxonomy.officialCodes().contains(merged.field("lclsSystm3"));
        var automatic = policy.qualify(merged, null, false, known);
        return new Preview(contentId, merged, DiscoverySourceHash.list(merged), DiscoverySourceHash.detail(merged),
                DiscoveryCandidatePolicy.fingerprint(merged), automatic);
    }

    public void approve(String contentId, DiscoveryCandidatePolicy.Role role, String expectedListHash,
                        String expectedDetailHash, String expectedFingerprintDigest, String evidenceRef,
                        String approvedBy, boolean detailReviewed, boolean rightsReviewed) {
        if (!detailReviewed || !rightsReviewed || evidenceRef == null || evidenceRef.isBlank()
                || approvedBy == null || approvedBy.isBlank()) throw new IllegalArgumentException("Review evidence is required");
        Preview preview = preview(contentId);
        if (!preview.listHash().equals(expectedListHash) || !preview.detailHash().equals(expectedDetailHash)
                || !preview.fingerprintDigest().equals(expectedFingerprintDigest)) throw new IllegalStateException("Candidate changed after preview");
        boolean known = taxonomy.officialCodes().contains(preview.row().field("lclsSystm3"));
        var human = new DiscoveryCandidatePolicy.HumanDecision(DiscoveryCandidatePolicy.Status.INCLUDE, role, preview.fingerprint());
        var decision = policy.qualify(preview.row(), human, false, known);
        if (decision.status() != DiscoveryCandidatePolicy.Status.INCLUDE || decision.role() != role)
            throw new IllegalStateException("Human approval cannot override invalid source or duplicate identity");
        store.saveApproval(contentId, new SelectedDiscoverySync.Approval(preview.listHash(), preview.detailHash(), role,
                preview.fingerprint(), true, true, evidenceRef, approvedBy));
    }

    public void revoke(String contentId, String reviewedBy) {
        if (reviewedBy == null || reviewedBy.isBlank()) throw new IllegalArgumentException("reviewedBy required");
        store.revokeApproval(contentId, reviewedBy);
    }

    public interface CandidateStore {
        SourceRecord latestCandidate(String contentId);
        void saveApproval(String contentId, SelectedDiscoverySync.Approval approval);
        void revokeApproval(String contentId, String reviewedBy);
    }
    public record Preview(String contentId, SourceRecord row, String listHash, String detailHash,
                          String fingerprint, DiscoveryCandidatePolicy.Decision automaticDecision) {
        public String fingerprintDigest() {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
                var result = new StringBuilder(64);
                for (byte value : hash) result.append(String.format("%02x", value));
                return result.toString();
            } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
        }
    }
}
