package com.yrootlab.onmaru.journey.thread;

import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyThreadServiceTests {

    private JourneyThreadStore store;
    private JourneyThreadService service;
    private Clock clock;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-09-17T12:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);
        store = new InMemoryJourneyThreadStore();
        service = new JourneyThreadService(store, clock);
    }

    @Test
    @DisplayName("여정 탐색 기록을 생성하고 턴 메모리가 올바르게 저장된다")
    void recordAndGetThread() {
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();

        var thread = service.recordOrSync(
                memberId,
                explorationId,
                "전주 한옥 탐색",
                "전주 한옥마을 조용한 곳 찾아줘 (010-1111-2222)",
                ExplorationRunOutcome.INITIAL_BOARD,
                ExplorationRunStatus.COMPLETED,
                null,
                1,
                3,
                runId);

        assertThat(thread.memberId()).isEqualTo(memberId);
        assertThat(thread.explorationId()).isEqualTo(explorationId);
        assertThat(thread.lastUserQueryPreview()).contains("[REDACTED_PHONE]");

        var detail = service.get(memberId, thread.threadId());
        assertThat(detail.thread().threadId()).isEqualTo(thread.threadId());
        assertThat(detail.turnHistory()).hasSize(1);
        assertThat(detail.turnHistory().get(0).redactedQuery()).contains("[REDACTED_PHONE]");
        assertThat(detail.turnHistory().get(0).redactionFlags()).contains("PHONE");
        assertThat(detail.explorationSnapshotUrl()).isEqualTo("/api/v1/explorations/" + explorationId);
    }

    @Test
    @DisplayName("다른 회원의 thread 조회 시 NotFound 예외가 발생한다")
    void getOtherMemberThreadThrowsNotFound() {
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();
        var explorationId = UUID.randomUUID();

        var thread = service.recordOrSync(
                member1,
                explorationId,
                "회원1의 탐색",
                "질문",
                ExplorationRunOutcome.INITIAL_BOARD,
                ExplorationRunStatus.COMPLETED,
                null,
                0,
                2,
                UUID.randomUUID());

        assertThatThrownBy(() -> service.get(member2, thread.threadId()))
                .isInstanceOf(JourneyThreadNotFoundException.class);
    }

    @Test
    @DisplayName("목록 조회 시 페이징과 커서가 올바르게 작동한다")
    void listWithPagination() {
        var memberId = UUID.randomUUID();

        for (int i = 1; i <= 5; i++) {
            service.recordOrSync(
                    memberId,
                    UUID.randomUUID(),
                    "탐색 " + i,
                    "질문 " + i,
                    ExplorationRunOutcome.INITIAL_BOARD,
                    ExplorationRunStatus.COMPLETED,
                    null,
                    0,
                    1,
                    UUID.randomUUID());
        }

        var page1 = service.list(memberId, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();

        var page2 = service.list(memberId, 2, page1.nextCursor());
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.hasMore()).isTrue();

        var page3 = service.list(memberId, 2, page2.nextCursor());
        assertThat(page3.items()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    @DisplayName("삭제된 thread는 목록과 상세 조회에서 제외된다")
    void deleteSoftDeletesThread() {
        var memberId = UUID.randomUUID();
        var thread = service.recordOrSync(
                memberId,
                UUID.randomUUID(),
                "삭제 대상 탐색",
                "질문",
                ExplorationRunOutcome.INITIAL_BOARD,
                ExplorationRunStatus.COMPLETED,
                null,
                0,
                1,
                UUID.randomUUID());

        service.delete(memberId, thread.threadId());

        var list = service.list(memberId, 10, null);
        assertThat(list.items()).isEmpty();

        assertThatThrownBy(() -> service.get(memberId, thread.threadId()))
                .isInstanceOf(JourneyThreadNotFoundException.class);
    }
}
