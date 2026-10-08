package com.yrootlab.onmaru.catalog.application.sourcefetch;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedSourceFetchTests {
    @Test
    void deduplicatesAcrossPagesAndQueriesAndPreservesRawFields() {
        var observer = new RecordingObserver();
        var fetch = new SelectedSourceFetch((query, number, size) -> {
            if (query.filter().equals("HS010100")) {
                return number == 1 ? page(1, 2, 3, row("1", "HS010100", "20261008010101"), row("2", "HS010100", "20261008010202"))
                        : page(2, 2, 3, row("3", "HS010100", "20261008010303"));
            }
            if (query.filter().equals("한옥")) return page(1, 2, 2, row("1", "HS010100", "20261008010101"), row("4", "AC010100", "20261008010404"));
            return page(1, 2, 0);
        }, Set.of("HS010100", "AC010100"), 2);

        var snapshot = fetch.fetch(observer);
        assertThat(snapshot.candidates()).hasSize(4);
        assertThat(snapshot.candidates().get("4").field("modifiedtime")).isEqualTo("20261008010404");
        assertThat(snapshot.provenance().get("1")).containsExactlyInAnyOrder("areaBasedList2:HS010100", "searchKeyword2:한옥");
        assertThat(snapshot.pages()).isEqualTo(39);
        assertThat(observer.completed).isSameAs(snapshot);
        assertThat(observer.checkpoints).hasSize(39);
    }

    @Test
    void incompletePageFailsWithoutCompletedSnapshot() {
        var observer = new RecordingObserver();
        var fetch = new SelectedSourceFetch((query, number, size) -> page(1, 2, 3, row("1", "HS010100", "x")),
                Set.of("HS010100"), 2);
        assertThatThrownBy(() -> fetch.fetch(observer)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete TourAPI page");
        assertThat(observer.completed).isNull();
    }

    @Test
    void changingTotalFailsWithoutCompletedSnapshot() {
        var observer = new RecordingObserver();
        var fetch = new SelectedSourceFetch((query, number, size) -> number == 1
                ? page(1, 2, 3, row("1", "HS010100", "x"), row("2", "HS010100", "x"))
                : page(2, 2, 4, row("3", "HS010100", "x"), row("4", "HS010100", "x")),
                Set.of("HS010100"), 2);
        assertThatThrownBy(() -> fetch.fetch(observer)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid TourAPI page");
        assertThat(observer.completed).isNull();
    }

    @Test
    void conflictingAndUnknownRecordsAreQuarantinedWithoutLosingOtherCandidates() {
        var fetch = new SelectedSourceFetch((query, number, size) -> {
            if (query.filter().equals("HS010100")) return page(1, 3, 2,
                    row("1", "HS010100", "old"), row("2", "HS010100", "same"));
            if (query.filter().equals("한옥")) return page(1, 3, 2,
                    row("1", "HS010100", "new"), row("3", "ZZ999999", "same"));
            return page(1, 3, 0);
        }, Set.of("HS010100"), 3);
        var snapshot = fetch.fetch(new RecordingObserver());
        assertThat(snapshot.candidates()).containsOnlyKeys("2");
        assertThat(snapshot.quarantine()).extracting(SelectedSourceFetch.Quarantine::reason)
                .containsExactly("CONFLICTING_PAYLOAD", "CONFLICTING_PAYLOAD", "UNKNOWN_CLASSIFICATION");
    }

    @Test
    void mismatchedSelectedClassificationIsQuarantinedButKeywordRescueCanUseIt() {
        var fetch = new SelectedSourceFetch((query, number, size) -> {
            if (query.filter().equals("HS010100")) return page(1, 2, 2,
                    row("1", "AC010100", "same"), row("2", "HS010100", "same"));
            if (query.filter().equals("한옥")) return page(1, 2, 1, row("3", "AC010100", "same"));
            return page(1, 2, 0);
        }, Set.of("HS010100", "AC010100"), 2);
        var snapshot = fetch.fetch(new RecordingObserver());
        assertThat(snapshot.candidates()).containsOnlyKeys("2", "3");
        assertThat(snapshot.quarantine()).extracting(SelectedSourceFetch.Quarantine::reason)
                .containsExactly("CLASSIFICATION_MISMATCH");
    }

    private static SelectedSourceFetch.Page page(int number, int size, int total, SourceRecord... records) {
        return new SelectedSourceFetch.Page(number, size, total, List.of(records));
    }

    private static SourceRecord row(String id, String code, String modified) {
        return new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of(
                "contentid", id, "lclsSystm3", code, "modifiedtime", modified));
    }

    private static final class RecordingObserver implements SelectedSourceFetch.Observer {
        final List<SelectedSourceFetch.Checkpoint> checkpoints = new ArrayList<>();
        SelectedSourceFetch.Snapshot completed;
        public void checkpoint(SelectedSourceFetch.Checkpoint checkpoint) { checkpoints.add(checkpoint); }
        public void completed(SelectedSourceFetch.Snapshot snapshot) { completed = snapshot; }
    }
}
