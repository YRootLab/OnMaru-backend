package com.yrootlab.onmaru.web.review.query;

import com.yrootlab.onmaru.catalog.publicid.CatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.community.command.review.ReviewIdGenerator;
import com.yrootlab.onmaru.community.command.review.VisitReviewCommandService;
import com.yrootlab.onmaru.community.command.review.VisitReviewPlace;
import com.yrootlab.onmaru.community.command.review.VisitReviewPlaceLookup;
import com.yrootlab.onmaru.community.like.VisitReviewLikeService;
import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
import com.yrootlab.onmaru.community.moderation.ModerationQueueService;
import com.yrootlab.onmaru.community.moderation.ReviewReportIdGenerator;
import com.yrootlab.onmaru.community.moderation.ReviewReportStore;
import com.yrootlab.onmaru.community.moderation.VisitReviewModerationService;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.MutableVisitReviewStore;
import com.yrootlab.onmaru.community.query.RegionVisitorCountLookup;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.InMemoryIdempotencyStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewPlaceLookup;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewStore;
import com.yrootlab.onmaru.persistence.community.JdbcReviewReportStore;
import com.yrootlab.onmaru.persistence.web.JdbcIdempotencyStore;
import com.yrootlab.onmaru.persistence.insights.JdbcVisitorObservationStore;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
class VisitReviewQueryConfiguration {

    @Bean
    @Profile("!production")
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
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    JdbcTransactionRunner jdbcTransactionRunner(DataSource dataSource) {
        return new JdbcTransactionRunner(dataSource);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(CatalogPublicPlaceIdStore.class)
    CatalogPublicPlaceIdStore catalogPublicPlaceIdStore(DataSource dataSource) {
        return new JdbcCatalogPublicPlaceIdStore(dataSource);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(MutableVisitReviewStore.class)
    MutableVisitReviewStore jdbcVisitReviewStore(
            DataSource dataSource,
            CatalogPublicPlaceIdStore catalogPublicPlaceIdStore,
            JdbcTransactionRunner jdbcTransactionRunner) {
        return new JdbcVisitReviewStore(dataSource, catalogPublicPlaceIdStore, jdbcTransactionRunner);
    }

    @Bean
    VisitReviewQueryService visitReviewQueryService(
            MutableVisitReviewStore store,
            RegionVisitorCountLookup visitorCountLookup,
            Clock clock) {
        return new VisitReviewQueryService(store, visitorCountLookup, clock);
    }

    @Bean
    @Profile("!production")
    RegionVisitorCountLookup emptyRegionVisitorCountLookup() {
        return ignored -> java.util.Map.of();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    RegionVisitorCountLookup jdbcRegionVisitorCountLookup(DataSource dataSource) {
        return new JdbcVisitorObservationStore(dataSource);
    }

    @Bean
    VisitReviewLikeService visitReviewLikeService(MutableVisitReviewStore store) {
        return new VisitReviewLikeService(store);
    }

    @Bean
    @Profile("!production")
    InMemoryReviewReportStore reviewReportStore() {
        return new InMemoryReviewReportStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    ReviewReportStore jdbcReviewReportStore(
            DataSource dataSource, JdbcTransactionRunner jdbcTransactionRunner) {
        return new JdbcReviewReportStore(dataSource, jdbcTransactionRunner);
    }

    @Bean
    VisitReviewModerationService visitReviewModerationService(
            MutableVisitReviewStore reviewStore,
            ReviewReportStore reportStore,
            Clock clock) {
        return new VisitReviewModerationService(
                reviewStore,
                reportStore,
                sequentialUuidGenerator(900),
                sequentialUuidGenerator(1900),
                clock);
    }

    @Bean
    ModerationQueueService moderationQueueService(
            MutableVisitReviewStore reviewStore,
            ReviewReportStore reportStore,
            Clock clock) {
        return new ModerationQueueService(reviewStore, reportStore, clock);
    }

    @Bean
    VisitReviewCommandService visitReviewCommandService(
            MutableVisitReviewStore store,
            VisitReviewPlaceLookup placeLookup,
            ReviewIdGenerator reviewIdGenerator,
            Clock clock) {
        return new VisitReviewCommandService(store, placeLookup, reviewIdGenerator, clock);
    }

    @Bean
    @Profile("!production")
    VisitReviewPlaceLookup visitReviewPlaceLookup() {
        return placeId -> switch (placeId) {
            case "p-jeonju-hanok-village" -> Optional.of(new VisitReviewPlace(
                    placeId,
                    "전주 한옥마을",
                    "kr-45-jeonju",
                    35.8151,
                    127.1530));
            case "p-bukchon-hanok-cafe" -> Optional.of(new VisitReviewPlace(
                    placeId,
                    "북촌 한옥 찻집",
                    "kr-11-jongno",
                    37.5824,
                    126.9836));
            default -> Optional.empty();
        };
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    VisitReviewPlaceLookup jdbcVisitReviewPlaceLookup(DataSource dataSource) {
        return new JdbcVisitReviewPlaceLookup(dataSource);
    }

    @Bean
    ReviewIdGenerator reviewIdGenerator() {
        var sequence = new AtomicLong();
        return () -> new UUID(0, sequence.incrementAndGet());
    }

    private ReviewReportIdGenerator sequentialUuidGenerator(long offset) {
        var sequence = new AtomicLong(offset);
        return () -> new UUID(0, sequence.incrementAndGet());
    }

    @Bean
    @Profile("!production")
    IdempotencyService idempotencyService(Clock clock) {
        return new IdempotencyService(new InMemoryIdempotencyStore(), clock);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    IdempotencyService jdbcIdempotencyService(
            DataSource dataSource, JdbcTransactionRunner jdbcTransactionRunner, Clock clock) {
        return new IdempotencyService(new JdbcIdempotencyStore(dataSource, jdbcTransactionRunner), clock);
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
