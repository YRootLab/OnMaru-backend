package com.yrootlab.onmaru.web.review.query;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Configuration
class VisitReviewQueryConfiguration {

    @Bean
    InMemoryVisitReviewStore visitReviewStore() {
        var store = new InMemoryVisitReviewStore();
        var memberId = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
        var otherMemberId = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
        store.add(review("00000000-0000-0000-0000-000000000001", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T01:00:00Z"), otherMemberId));
        store.add(review("00000000-0000-0000-0000-000000000002", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T02:00:00Z"), otherMemberId));
        store.add(review("00000000-0000-0000-0000-000000000003", "p-bukchon-hanok-cafe",
                "kr-11-jongno", Instant.parse("2026-09-15T03:00:00Z"), memberId, memberId));
        return store;
    }

    @Bean
    VisitReviewQueryService visitReviewQueryService(InMemoryVisitReviewStore store, Clock clock) {
        return new VisitReviewQueryService(store, clock);
    }

    private VisitReviewProjection review(
            String reviewId,
            String placeId,
            String regionCode,
            Instant createdAt,
            UUID authorId,
            UUID... likedBy) {
        return new VisitReviewProjection(
                UUID.fromString(reviewId),
                placeId,
                placeId.equals("p-jeonju-hanok-village") ? "전주 한옥마을" : "북촌 한옥 찻집",
                regionCode,
                placeId.equals("p-jeonju-hanok-village") ? 35.8151 : 37.5824,
                placeId.equals("p-jeonju-hanok-village") ? 127.1530 : 126.9836,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                createdAt,
                authorId,
                Set.of(likedBy),
                VisitReviewStatus.PUBLISHED);
    }
}
