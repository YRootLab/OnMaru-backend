package com.yrootlab.onmaru.catalog.application.selectedsync;

import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.sourcefetch.SelectedSourceFetch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedDiscoverySyncTests {
    private static final Instant DUE = Instant.parse("2026-10-12T18:00:00Z");

    @Test
    void manualSlotsInOneWeekAllowDryRunThenProgressiveApprovalWithoutReusingARevision() {
        var store = new MemoryStore(); var fixture = new Fixture();
        var first = row("1", "HS010100", "첫 궁궐", "same");
        var second = row("2", "HS010100", "둘째 궁궐", "same");
        fixture.rows = List.of(first, second);
        var service = fixture.service(store, 3000);
        UUID dryRun = UUID.randomUUID();
        assertThat(service.run(dryRun, DUE)).isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(service.run(dryRun, DUE)).isEqualTo(SelectedDiscoverySync.Result.ALREADY_PROCESSED);
        assertThat(store.activeRevision).isNull();

        store.approvals.put("1", approval(first, fixture.overview, DiscoveryCandidatePolicy.Role.CORE_TRADITIONAL_PLACE));
        UUID pilot = UUID.randomUUID();
        assertThat(service.run(pilot, DUE.plusSeconds(60))).isEqualTo(SelectedDiscoverySync.Result.PUBLISHED);
        assertThat(store.activePublic).containsOnlyKeys("1");
        assertThat(service.run(pilot, DUE.plusSeconds(60))).isEqualTo(SelectedDiscoverySync.Result.ALREADY_PROCESSED);

        store.approvals.put("2", approval(second, fixture.overview, DiscoveryCandidatePolicy.Role.CORE_TRADITIONAL_PLACE));
        UUID expanded = UUID.randomUUID();
        assertThat(service.run(expanded, DUE.plusSeconds(120))).isEqualTo(SelectedDiscoverySync.Result.PUBLISHED);
        assertThat(store.activeRevision).isEqualTo(expanded);
        assertThat(store.activePublic).containsOnlyKeys("1", "2");
        assertThat(store.claimed).containsExactlyInAnyOrder(dryRun, pilot, expanded);
    }

    @Test
    void detectsDetailOnlyChangeWithSameModifiedTimeAndKeepsLastGoodRevision() {
        var store = new MemoryStore();
        var fixture = new Fixture();
        fixture.rows = List.of(row("1", "HS010100", "궁궐", "20261008010101"));
        fixture.overview = "촬영지 상세 A";
        var service = fixture.service(store, 3000);
        UUID first = UUID.randomUUID();
        assertThat(service.run(first, DUE)).isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(store.lastReport.added()).isEqualTo(1);
        assertThat(store.activeRevision).isNull();

        store.approvals.put("1", approval(fixture.rows.getFirst(), fixture.overview,
                DiscoveryCandidatePolicy.Role.CORE_TRADITIONAL_PLACE));
        UUID published = UUID.randomUUID();
        assertThat(service.run(published, DUE.plusSeconds(604800))).isEqualTo(SelectedDiscoverySync.Result.PUBLISHED);
        assertThat(store.activeRevision).isEqualTo(published);
        assertThat(store.lastReport.unchanged()).isEqualTo(1);
        assertThat(store.lastReport.detailRequests()).isEqualTo(1);

        fixture.overview = "촬영지 상세 B";
        UUID changed = UUID.randomUUID();
        assertThat(service.run(changed, DUE.plusSeconds(1209600))).isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(store.lastReport.changed()).isEqualTo(1);
        assertThat(store.activeRevision).isEqualTo(published);
        assertThat(store.lastReport.approved()).isZero();
        assertThat(store.candidates.get("1").modifiedtime()).isEqualTo("20261008010101");
    }

    @Test
    void modifiedTimeChangeWithoutHashChangeDoesNotCreateChangedWork() {
        var store = new MemoryStore(); var fixture = new Fixture();
        fixture.rows = List.of(row("1", "HS010100", "궁궐", "old"));
        var service = fixture.service(store, 3000);
        service.run(UUID.randomUUID(), DUE);
        fixture.rows = List.of(row("1", "HS010100", "궁궐", "new"));
        service.run(UUID.randomUUID(), DUE.plusSeconds(604800));
        assertThat(store.lastReport.unchanged()).isEqualTo(1);
        assertThat(store.lastReport.changed()).isZero();
    }

    @Test
    void humanApprovedHanokCafePublishesWhileOrdinaryCafeStaysOut() {
        var store = new MemoryStore(); var fixture = new Fixture();
        var hanok = row("1", "FD050100", "한옥 카페", "same");
        var ordinary = row("2", "FD050100", "도심 카페", "same");
        fixture.rows = List.of(hanok, ordinary);
        fixture.overview = "상세 공간 확인";
        store.approvals.put("1", approval(hanok, fixture.overview, DiscoveryCandidatePolicy.Role.SURROUNDING_CULTURE));
        UUID run = UUID.randomUUID();
        assertThat(fixture.service(store, 3000).run(run, DUE)).isEqualTo(SelectedDiscoverySync.Result.PUBLISHED);
        assertThat(store.activePublic).containsOnlyKeys("1");
        assertThat(store.candidates.get("1").decision().reasonCode()).isEqualTo("HUMAN_CONFIRMED");
        assertThat(store.candidates.get("2").decision().status()).isEqualTo(DiscoveryCandidatePolicy.Status.EXCLUDE);
        assertThat(store.lastReport.byRole()).containsEntry("SURROUNDING_CULTURE", 1L);
        assertThat(store.lastReport.byRegion()).containsEntry("11", 1L);
    }

    @Test
    void approvalWithdrawalAndMissingSourceNeverDeleteActivePublishedRows() {
        var store = new MemoryStore(); var fixture = new Fixture();
        var row = row("1", "HS010100", "궁궐", "same");
        fixture.rows = List.of(row); fixture.overview = "상세 공간 확인";
        store.approvals.put("1", approval(row, fixture.overview, DiscoveryCandidatePolicy.Role.CORE_TRADITIONAL_PLACE));
        UUID active = UUID.randomUUID();
        assertThat(fixture.service(store, 3000).run(active, DUE)).isEqualTo(SelectedDiscoverySync.Result.PUBLISHED);
        store.approvals.clear();
        assertThat(fixture.service(store, 3000).run(UUID.randomUUID(), DUE.plusSeconds(604800)))
                .isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(store.activeRevision).isEqualTo(active);
        fixture.rows = List.of(row("2", "HS010100", "다른 궁궐", "same"));
        assertThat(fixture.service(store, 3000).run(UUID.randomUUID(), DUE.plusSeconds(1209600)))
                .isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(store.lastReport.missing()).isEqualTo(1);
        assertThat(store.activeRevision).isEqualTo(active);
    }

    @Test
    void detailBudgetFailureRecordsCountAndKeepsPointer() {
        var store = new MemoryStore(); var fixture = new Fixture();
        fixture.rows = List.of(row("1", "HS010100", "첫 궁궐", "same"), row("2", "HS010100", "둘째 궁궐", "same"));
        assertThatThrownBy(() -> fixture.service(store, 1).run(UUID.randomUUID(), DUE))
                .isInstanceOf(SelectedDiscoverySync.DetailBudgetExceeded.class);
        assertThat(store.failureCode).isEqualTo("DETAIL_BUDGET_EXHAUSTED");
        assertThat(store.detailProgress).isEqualTo(1);
        assertThat(store.activeRevision).isNull();
    }

    @Test
    void missingPageFailsWithoutStagingOrPointerChangeAndSameRunIsIdempotent() {
        var store = new MemoryStore(); var fixture = new Fixture();
        fixture.rows = List.of(row("1", "HS010100", "궁궐", "same"));
        var service = fixture.service(store, 3000);
        UUID run = UUID.randomUUID();
        assertThat(service.run(run, DUE)).isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(service.run(run, DUE)).isEqualTo(SelectedDiscoverySync.Result.ALREADY_PROCESSED);
        fixture.badPage = true;
        assertThatThrownBy(() -> service.run(UUID.randomUUID(), DUE.plusSeconds(604800)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.activeRevision).isNull();
        assertThat(store.failureCode).isEqualTo("SELECTED_SYNC_FAILED");
    }

    @Test
    void unknownTaxonomyQuarantineIsRetainedAsReviewWithOriginalPayload() {
        var store = new MemoryStore(); var fixture = new Fixture();
        fixture.rows = List.of(row("1", "HS010100", "궁궐", "same"));
        fixture.keywordRows = List.of(row("2", "ZZ999999", "한옥 새 분류", "same"));
        assertThat(fixture.service(store, 3000).run(UUID.randomUUID(), DUE)).isEqualTo(SelectedDiscoverySync.Result.STAGED);
        assertThat(store.reviews).hasSize(1);
        assertThat(store.reviews.getFirst().row().field("lclsSystm3")).isEqualTo("ZZ999999");
        assertThat(store.reviews.getFirst().decision().status()).isEqualTo(DiscoveryCandidatePolicy.Status.REVIEW);
        assertThat(store.reviews.getFirst().decision().reasonCode()).isEqualTo("UNKNOWN_SOURCE_CODE");
    }

    private static SourceRecord row(String id, String code, String title, String modified) {
        return new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of(
                "contentid", id, "lclsSystm3", code, "title", title, "mapx", "127.0", "mapy", "37.5",
                "areacode", "11", "modifiedtime", modified));
    }

    private static SelectedDiscoverySync.Approval approval(SourceRecord list, String overview, DiscoveryCandidatePolicy.Role role) {
        var fields = new HashMap<>(list.fields()); fields.put("overview", overview);
        var merged = new SourceRecord(list.provider(), list.operation(), fields);
        return new SelectedDiscoverySync.Approval(DiscoverySourceHash.list(merged), DiscoverySourceHash.detail(merged),
                role, DiscoveryCandidatePolicy.fingerprint(merged), true, true, "https://example.org/review", "reviewer");
    }

    private static final class Fixture {
        List<SourceRecord> rows = List.of(); List<SourceRecord> keywordRows = List.of();
        String overview = "검증된 상세"; boolean badPage;
        SelectedDiscoverySync service(MemoryStore store, int budget) {
            SelectedSourceFetch.PageSource source = (query, page, size) -> {
                if (badPage) throw new IllegalStateException("page timeout");
                List<SourceRecord> matching = query.operation().equals("areaBasedList2")
                        ? rows.stream().filter(row -> query.filter().equals(row.field("lclsSystm3"))).toList()
                        : query.filter().equals("한옥") ? keywordRows : List.of();
                return new SelectedSourceFetch.Page(page, size, matching.size(), matching);
            };
            Set<String> codes = new java.util.HashSet<>(SelectedSourceFetch.CODES);
            codes.add("AC010100");
            return new SelectedDiscoverySync(source, () -> codes, row -> Map.of("overview", overview), store, 1000, budget);
        }
    }

    private static final class MemoryStore implements SelectedDiscoverySync.Store {
        final Set<UUID> claimed = new java.util.HashSet<>();
        final Map<String, SelectedDiscoverySync.Approval> approvals = new HashMap<>();
        Map<String, SelectedDiscoverySync.Candidate> candidates = Map.of();
        Map<String, SelectedDiscoverySync.PublicItem> activePublic = Map.of();
        List<SelectedDiscoverySync.Review> reviews = List.of();
        UUID activeRevision; SelectedDiscoverySync.Report lastReport; String failureCode; int detailProgress;
        public boolean claim(UUID runId, Instant dueAt) { return claimed.add(runId); }
        public void checkpoint(UUID runId, SelectedSourceFetch.Checkpoint checkpoint) { }
        public void detailProgress(UUID runId, int requests) { detailProgress = requests; }
        public Map<String, SelectedDiscoverySync.Candidate> previousCandidates() { return candidates; }
        public Map<String, SelectedDiscoverySync.PublicItem> activePublic() { return activePublic; }
        public Map<String, SelectedDiscoverySync.Approval> approvals() { return approvals; }
        public UUID activeRevisionId() { return activeRevision; }
        public void stage(UUID runId, Map<String, SelectedDiscoverySync.Candidate> records,
                          List<SelectedDiscoverySync.Review> reviews, SelectedDiscoverySync.Report report) {
            candidates = new LinkedHashMap<>(records); this.reviews = List.copyOf(reviews); lastReport = report;
        }
        public boolean publish(UUID runId, UUID expected, Map<String, SelectedDiscoverySync.PublicItem> items,
                               SelectedDiscoverySync.Report report) {
            if (!java.util.Objects.equals(activeRevision, expected)) return false;
            activeRevision = runId; activePublic = Map.copyOf(items); return true;
        }
        public void fail(UUID runId, String code) { failureCode = code; }
    }
}
