package com.yrootlab.onmaru.catalog.application.selectedsync;

import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedDiscoveryApprovalServiceTests {
    @Test void approvedFingerprintUsesMergedDetailAndRejectsStalePreview() {
        var candidate = new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of(
                "contentid", "1", "title", "한옥 카페", "lclsSystm3", "FD050100",
                "mapx", "127.0", "mapy", "37.5"));
        var store = new MemoryStore(candidate);
        final String[] overview = {"한옥 건물 확인"};
        var service = new SelectedDiscoveryApprovalService(store, row -> Map.of("overview", overview[0]),
                () -> Set.of("FD050100"));
        var preview = service.preview("1");
        assertThat(preview.automaticDecision().status()).isEqualTo(DiscoveryCandidatePolicy.Status.REVIEW);
        assertThat(preview.fingerprint()).isEqualTo(DiscoveryCandidatePolicy.fingerprint(preview.row()));
        assertThat(preview.row().field("overview")).isEqualTo("한옥 건물 확인");
        overview[0] = "다른 건물";
        assertThatThrownBy(() -> service.approve("1", DiscoveryCandidatePolicy.Role.SURROUNDING_CULTURE,
                preview.listHash(), preview.detailHash(), preview.fingerprintDigest(),
                "https://example.org/review", "reviewer", true, true)).hasMessageContaining("changed after preview");
        assertThat(store.approval).isNull();
        var fresh = service.preview("1");
        service.approve("1", DiscoveryCandidatePolicy.Role.SURROUNDING_CULTURE,
                fresh.listHash(), fresh.detailHash(), fresh.fingerprintDigest(),
                "https://example.org/review", "reviewer", true, true);
        assertThat(store.approval.sourceFingerprint()).isEqualTo(DiscoveryCandidatePolicy.fingerprint(fresh.row()));
        service.revoke("1", "reviewer");
        assertThat(store.approval).isNull();
    }

    private static final class MemoryStore implements SelectedDiscoveryApprovalService.CandidateStore {
        private final SourceRecord candidate;
        SelectedDiscoverySync.Approval approval;
        MemoryStore(SourceRecord candidate) { this.candidate = candidate; }
        public SourceRecord latestCandidate(String contentId) { return candidate; }
        public void saveApproval(String contentId, SelectedDiscoverySync.Approval approval) { this.approval = approval; }
        public void revokeApproval(String contentId, String reviewedBy) { approval = null; }
    }
}
