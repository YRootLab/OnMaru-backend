package com.yrootlab.onmaru.catalog.application.sourcefetch;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A complete candidate snapshot is returned only after every planned page has passed validation.
 * The caller supplies the frozen official taxonomy codes used by qualification; unknown codes stay
 * in {@link Snapshot#quarantine()} with their original record so the next stage can review them.
 */
public final class SelectedSourceFetch {
    public static final String POLICY_VERSION = "discovery-candidate-v1.0.0";
    public static final List<String> CODES = List.of(("HS010100 HS010200 HS010300 HS010400 HS010500 HS010600 EX010100 AC030200 VE040100 VE040200 "
            + "HS010700 HS011100 HS011200 EX040200 HS020100 HS020300 HS030100 VE070100 VE070200 VE070300 "
            + "VE090100 VE090400 FD040400 FD050200 SH050100 SH060100 SH060200 NA040700 VE010100 VE010900 "
            + "EX060100 EX060300 FD050100 EX030100").split(" "));
    public static final List<String> KEYWORDS = List.of("한옥", "고택", "궁궐", "전통마을");

    private final PageSource source;
    private final Set<String> knownCodes;
    private final int pageSize;

    /** {@code knownCodes} must be the same official taxonomy registry passed to downstream qualification. */
    public SelectedSourceFetch(PageSource source, Set<String> knownCodes, int pageSize) {
        this.source = Objects.requireNonNull(source);
        this.knownCodes = Set.copyOf(knownCodes);
        if (pageSize < 1 || pageSize > 1000) throw new IllegalArgumentException("pageSize must be 1..1000");
        this.pageSize = pageSize;
    }

    public Snapshot fetch(Observer observer) {
        Objects.requireNonNull(observer);
        Map<String, SourceRecord> staged = new LinkedHashMap<>();
        Map<String, Set<String>> provenance = new LinkedHashMap<>();
        List<Quarantine> quarantine = new ArrayList<>();
        Set<String> conflicts = new LinkedHashSet<>();
        int pages = 0;
        int received = 0;
        for (String code : CODES) {
            Counts counts = collect(new Query("areaBasedList2", code), staged, provenance, quarantine, conflicts, observer);
            pages += counts.pages(); received += counts.received();
        }
        for (String keyword : KEYWORDS) {
            Counts counts = collect(new Query("searchKeyword2", keyword), staged, provenance, quarantine, conflicts, observer);
            pages += counts.pages(); received += counts.received();
        }
        Snapshot snapshot = new Snapshot(POLICY_VERSION, Map.copyOf(staged), List.copyOf(quarantine),
                provenance.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> Set.copyOf(e.getValue()))), pages, received);
        observer.completed(snapshot);
        return snapshot;
    }

    private Counts collect(Query query, Map<String, SourceRecord> staged, Map<String, Set<String>> provenance,
                           List<Quarantine> quarantine, Set<String> conflicts, Observer observer) {
        int total = -1;
        int received = 0;
        int pageNumber = 1;
        while (true) {
            Page page = source.fetch(query, pageNumber, pageSize);
            if (page == null || page.pageNumber() != pageNumber || page.pageSize() != pageSize
                    || page.totalCount() < 0 || page.records() == null || page.records().size() > pageSize
                    || (total >= 0 && total != page.totalCount())) {
                throw new IllegalStateException("Invalid TourAPI page: " + query + " page=" + pageNumber);
            }
            if (total < 0) total = page.totalCount();
            int expected = Math.min(pageSize, Math.max(0, total - received));
            if (page.records().size() != expected) {
                throw new IllegalStateException("Incomplete TourAPI page: " + query + " page=" + pageNumber
                        + " expected=" + expected + " received=" + page.records().size());
            }
            for (SourceRecord record : page.records()) {
                String id = record.field("contentid");
                if (id == null || !id.matches("[0-9]+")) {
                    quarantine.add(new Quarantine(query, id, "INVALID_CONTENT_ID", record));
                    continue;
                }
                String code = record.field("lclsSystm3");
                if (code == null || !knownCodes.contains(code)) {
                    quarantine.add(new Quarantine(query, id, "UNKNOWN_CLASSIFICATION", record));
                    continue;
                }
                if (query.operation().equals("areaBasedList2") && !query.filter().equals(code)) {
                    quarantine.add(new Quarantine(query, id, "CLASSIFICATION_MISMATCH", record));
                    continue;
                }
                provenance.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(query.operation() + ":" + query.filter());
                if (conflicts.contains(id)) {
                    quarantine.add(new Quarantine(query, id, "CONFLICTING_PAYLOAD", record));
                    continue;
                }
                SourceRecord previous = staged.putIfAbsent(id, record);
                if (previous != null && !previous.fields().equals(record.fields())) {
                    staged.remove(id);
                    conflicts.add(id);
                    quarantine.add(new Quarantine(query, id, "CONFLICTING_PAYLOAD", previous));
                    quarantine.add(new Quarantine(query, id, "CONFLICTING_PAYLOAD", record));
                }
            }
            received += page.records().size();
            observer.checkpoint(new Checkpoint(POLICY_VERSION, query, pageNumber, received, total,
                    staged.size(), quarantine.size()));
            if (received == total) return new Counts(pageNumber, received);
            pageNumber++;
        }
    }

    public interface PageSource { Page fetch(Query query, int pageNumber, int pageSize); }
    public interface Observer {
        void checkpoint(Checkpoint checkpoint);
        void completed(Snapshot snapshot);
    }
    public record Query(String operation, String filter) { }
    public record Page(int pageNumber, int pageSize, int totalCount, List<SourceRecord> records) { }
    public record Checkpoint(String policyVersion, Query query, int pageNumber, int received,
                             int expected, int candidateCount, int quarantineCount) { }
    public record Quarantine(Query query, String contentId, String reason, SourceRecord record) { }
    public record Snapshot(String policyVersion, Map<String, SourceRecord> candidates,
                           List<Quarantine> quarantine, Map<String, Set<String>> provenance,
                           int pages, int received) { }
    private record Counts(int pages, int received) { }
}
