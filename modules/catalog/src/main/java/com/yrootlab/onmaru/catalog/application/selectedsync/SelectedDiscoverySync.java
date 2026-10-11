package com.yrootlab.onmaru.catalog.application.selectedsync;

import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.sourcefetch.SelectedSourceFetch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Weekly candidate capture, diff, review, and separately approved discovery publication. */
public final class SelectedDiscoverySync {
    private final SelectedSourceFetch.PageSource pages;
    private final TaxonomyRegistry taxonomy;
    private final DetailSource details;
    private final Store store;
    private final DiscoveryCandidatePolicy policy;
    private final int pageSize;
    private final int maxDetailRequests;

    public SelectedDiscoverySync(SelectedSourceFetch.PageSource pages, TaxonomyRegistry taxonomy,
                                 DetailSource details, Store store, int pageSize) {
        this(pages, taxonomy, details, store, pageSize, 3000);
    }

    public SelectedDiscoverySync(SelectedSourceFetch.PageSource pages, TaxonomyRegistry taxonomy,
                                 DetailSource details, Store store, int pageSize, int maxDetailRequests) {
        this.pages = Objects.requireNonNull(pages);
        this.taxonomy = Objects.requireNonNull(taxonomy);
        this.details = Objects.requireNonNull(details);
        this.store = Objects.requireNonNull(store);
        this.policy = new DiscoveryCandidatePolicy();
        this.pageSize = pageSize;
        if (maxDetailRequests < 1) throw new IllegalArgumentException("maxDetailRequests must be positive");
        this.maxDetailRequests = maxDetailRequests;
    }

    public Result run(UUID runId, Instant dueAt) {
        Objects.requireNonNull(runId); Objects.requireNonNull(dueAt);
        if (!store.claim(runId, dueAt)) return Result.ALREADY_PROCESSED;
        try {
            Set<String> knownCodes = Set.copyOf(taxonomy.officialCodes());
            if (!knownCodes.containsAll(SelectedSourceFetch.CODES)) throw new IllegalStateException("Official taxonomy omits selected codes");
            // One immutable official registry is shared by W1 quarantine and W2 meaning policy.
            var snapshot = new SelectedSourceFetch(pages, knownCodes, pageSize).fetch(new SelectedSourceFetch.Observer() {
                public void checkpoint(SelectedSourceFetch.Checkpoint checkpoint) { store.checkpoint(runId, checkpoint); }
                public void completed(SelectedSourceFetch.Snapshot complete) { }
            });
            if (snapshot.candidates().isEmpty() || snapshot.received() == 0) {
                store.fail(runId, "EMPTY_SNAPSHOT");
                return Result.FAILED;
            }
            var previous = store.previousCandidates();
            UUID expectedActive = store.activeRevisionId();
            var active = store.activePublic();
            var approvals = store.approvals();
            Map<String, Candidate> staged = new LinkedHashMap<>();
            List<Review> reviews = new ArrayList<>();
            Map<String, PublicItem> publicItems = new LinkedHashMap<>();
            int added = 0, changed = 0, unchanged = 0, missing = 0;
            int detailRequests = 0;
            boolean publicSourceChanged = false;
            for (var entry : snapshot.candidates().entrySet()) {
                String id = entry.getKey();
                SourceRecord row = entry.getValue();
                Candidate prior = previous.get(id);
                String listHash = DiscoverySourceHash.list(row);
                boolean sameList = prior != null && prior.hashSchemaVersion().equals(DiscoverySourceHash.SCHEMA_VERSION)
                        && prior.listHash().equals(listHash);
                Approval approval = approvals.get(id);
                var preliminary = policy.qualify(row, null, false, knownCodes.contains(row.field("lclsSystm3")));
                boolean requiresDetail = approval != null
                        || preliminary.status() == DiscoveryCandidatePolicy.Status.INCLUDE
                        || prior != null && prior.decision().status() == DiscoveryCandidatePolicy.Status.INCLUDE;
                if (requiresDetail) {
                    if (detailRequests >= maxDetailRequests) throw new DetailBudgetExceeded(detailRequests);
                    detailRequests++;
                    store.detailProgress(runId, detailRequests);
                    row = merge(row, details.fetch(row));
                }
                String detailHash = requiresDetail || prior == null ? DiscoverySourceHash.detail(row) : prior.detailHash();
                Diff diff = prior == null ? Diff.ADDED : sameList && prior.detailHash().equals(detailHash) ? Diff.UNCHANGED : Diff.CHANGED;
                switch (diff) { case ADDED -> added++; case CHANGED -> changed++; case UNCHANGED -> unchanged++; default -> { } }
                var human = approval == null ? null : new DiscoveryCandidatePolicy.HumanDecision(
                        DiscoveryCandidatePolicy.Status.INCLUDE, approval.role(), approval.sourceFingerprint());
                DiscoveryCandidatePolicy.Decision decision = diff == Diff.UNCHANGED && approval == null
                        && prior.policyVersion().equals(DiscoveryCandidatePolicy.VERSION)
                        ? prior.decision() : policy.qualify(row, human, false, knownCodes.contains(row.field("lclsSystm3")));
                var candidate = new Candidate(id, row, listHash, detailHash, DiscoverySourceHash.SCHEMA_VERSION,
                        decision, diff, row.field("modifiedtime"));
                staged.put(id, candidate);
                if (active.containsKey(id) && diff != Diff.UNCHANGED) publicSourceChanged = true;
                if (approved(candidate, approval)) publicItems.put(id, new PublicItem(id, row, decision.role(), approval));
            }
            for (var entry : previous.entrySet()) {
                if (!staged.containsKey(entry.getKey())) {
                    staged.put(entry.getKey(), entry.getValue().withDiff(Diff.MISSING));
                    missing++;
                    if (active.containsKey(entry.getKey())) publicSourceChanged = true;
                }
            }
            for (var quarantine : snapshot.quarantine()) {
                var row = quarantine.record();
                var assessed = policy.qualify(row, null, "CONFLICTING_PAYLOAD".equals(quarantine.reason()),
                        knownCodes.contains(row.field("lclsSystm3")));
                // W1 quarantine is a review queue, even when W2 finds an invalid source reason.
                var review = new DiscoveryCandidatePolicy.Decision(DiscoveryCandidatePolicy.Status.REVIEW, null,
                        assessed.reasonCode(), DiscoveryCandidatePolicy.VERSION, false);
                reviews.add(new Review(quarantine.reason(), row, review));
            }
            var report = new Report(runId, added, changed, unchanged, missing, snapshot.quarantine().size(),
                    publicItems.size(), detailRequests, countsByRegion(publicItems), countsByRole(publicItems));
            store.stage(runId, staged, reviews, report);
            // A changed or missing published row needs review; never silently remove it in another publication.
            if (publicItems.isEmpty() || publicSourceChanged || !publicItems.keySet().containsAll(active.keySet())) return Result.STAGED;
            return store.publish(runId, expectedActive, publicItems, report) ? Result.PUBLISHED : Result.STAGED;
        } catch (RuntimeException exception) {
            store.fail(runId, exception instanceof DetailBudgetExceeded ? "DETAIL_BUDGET_EXHAUSTED" : "SELECTED_SYNC_FAILED");
            throw exception;
        }
    }

    private boolean approved(Candidate candidate, Approval approval) {
        if (approval == null || candidate.diff() == Diff.MISSING) return false;
        var decision = candidate.decision();
        return decision.status() == DiscoveryCandidatePolicy.Status.INCLUDE
                && approval.listHash().equals(candidate.listHash())
                && approval.detailHash().equals(candidate.detailHash())
                && approval.role() == decision.role()
                && approval.detailReviewed() && approval.rightsReviewed()
                && !approval.evidenceRef().isBlank() && !approval.approvedBy().isBlank()
                && !value(candidate.row().field("overview")).isBlank();
    }

    private static SourceRecord merge(SourceRecord row, Map<String, String> detail) {
        var fields = new HashMap<>(row.fields());
        detail.forEach((key, value) -> {
            if (Set.of("overview", "detail", "homepage", "usetime", "restdate", "parking", "infocenter").contains(key))
                fields.put(key, value);
        });
        return new SourceRecord(row.provider(), row.operation(), fields);
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static Map<String, Long> countsByRegion(Map<String, PublicItem> items) {
        Map<String, Long> counts = new HashMap<>();
        items.values().forEach(item -> counts.merge(value(item.row().field("areacode")), 1L, Long::sum));
        return Map.copyOf(counts);
    }

    private static Map<String, Long> countsByRole(Map<String, PublicItem> items) {
        Map<String, Long> counts = new HashMap<>();
        items.values().forEach(item -> counts.merge(item.role().name(), 1L, Long::sum));
        return Map.copyOf(counts);
    }

    public interface TaxonomyRegistry { Set<String> officialCodes(); }
    public interface DetailSource { Map<String, String> fetch(SourceRecord row); }
    public interface Store {
        boolean claim(UUID runId, Instant dueAt);
        void checkpoint(UUID runId, SelectedSourceFetch.Checkpoint checkpoint);
        void detailProgress(UUID runId, int requests);
        Map<String, Candidate> previousCandidates();
        Map<String, PublicItem> activePublic();
        Map<String, Approval> approvals();
        UUID activeRevisionId();
        void stage(UUID runId, Map<String, Candidate> candidates, List<Review> reviews, Report report);
        boolean publish(UUID runId, UUID expectedActive, Map<String, PublicItem> items, Report report);
        void fail(UUID runId, String code);
    }
    public enum Diff { ADDED, CHANGED, UNCHANGED, MISSING }
    public enum Result { PUBLISHED, STAGED, FAILED, ALREADY_PROCESSED }
    public record Candidate(String contentId, SourceRecord row, String listHash, String detailHash,
                            String hashSchemaVersion, DiscoveryCandidatePolicy.Decision decision, Diff diff,
                            String modifiedtime) {
        Candidate withDiff(Diff next) { return new Candidate(contentId, row, listHash, detailHash, hashSchemaVersion, decision, next, modifiedtime); }
        public String policyVersion() { return decision.policyVersion(); }
    }
    public record Approval(String listHash, String detailHash, DiscoveryCandidatePolicy.Role role,
                           String sourceFingerprint, boolean detailReviewed, boolean rightsReviewed,
                           String evidenceRef, String approvedBy) { }
    public record PublicItem(String contentId, SourceRecord row, DiscoveryCandidatePolicy.Role role, Approval approval) { }
    public record Review(String reason, SourceRecord row, DiscoveryCandidatePolicy.Decision decision) { }
    public record Report(UUID runId, int added, int changed, int unchanged, int missing, int quarantined,
                         int approved, int detailRequests, Map<String, Long> byRegion, Map<String, Long> byRole) { }
    public static final class DetailBudgetExceeded extends IllegalStateException {
        public DetailBudgetExceeded(int requests) { super("TourAPI detail budget exhausted after " + requests + " requests"); }
    }
}
