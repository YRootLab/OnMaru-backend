package com.yrootlab.onmaru.stamp.ranking;

import com.yrootlab.onmaru.stamp.CheckInPlaceLookup;
import com.yrootlab.onmaru.stamp.InMemoryStampStore;
import com.yrootlab.onmaru.stamp.StampService;
import com.yrootlab.onmaru.stamp.VerifiedPlace;
import com.yrootlab.onmaru.stamp.CheckInCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StampRankingServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PUBLIC_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant NOW = Instant.parse("2026-09-27T03:00:00Z");

    @Test
    void optsInWithGeneratedIdentityAndErasesItImmediatelyOnWithdrawal() {
        var store = new InMemoryStampStore();
        var service = rankingService(store, new QueueIdentityGenerator(identity(PUBLIC_ID, "고즈넉한여행자-A7K2")), NOW);

        assertThat(service.status(MEMBER_ID).participating()).isFalse();
        assertThat(service.status(MEMBER_ID).publicNickname()).isNull();
        assertThat(service.update(MEMBER_ID, true).publicNickname()).isEqualTo("고즈넉한여행자-A7K2");
        assertThat(service.update(MEMBER_ID, false).publicNickname()).isNull();
        assertThat(service.status(MEMBER_ID).rank()).isNull();
        assertThat(service.leaderboard(20).entries()).isEmpty();
    }

    @Test
    void normalizesGeneratedNicknameAndRejectsInvalidCodePointLength() {
        var normalized = identity(PUBLIC_ID, "  ＡB  ");

        assertThat(normalized.publicNickname()).isEqualTo("AB");
        assertThat(normalized.nicknameNormalized()).isEqualTo("ab");
        assertThat(normalized.nicknameType()).isEqualTo(StampRankingNicknameType.GENERATED);
        assertThat(identity(PUBLIC_ID, "\u00a0ＡＢ\u00a0").publicNickname()).isEqualTo("AB");
        assertThatThrownBy(() -> identity(PUBLIC_ID, "A"))
                .isInstanceOf(StampRankingInputInvalidException.class);
        assertThatThrownBy(() -> identity(PUBLIC_ID, "가".repeat(21)))
                .isInstanceOf(StampRankingInputInvalidException.class);
        assertThat(identity(PUBLIC_ID, "😀😀").publicNickname()).isEqualTo("😀😀");
    }

    @Test
    void retriesIdentityCollisionFiveTimesAndThenFails() {
        var store = new InMemoryStampStore();
        store.participate(MEMBER_ID, identity(PUBLIC_ID, "기존여행자-A001"), NOW);
        var candidates = new QueueIdentityGenerator(
                identity(PUBLIC_ID, "새여행자-A001"),
                identity(PUBLIC_ID, "새여행자-A002"),
                identity(PUBLIC_ID, "새여행자-A003"),
                identity(PUBLIC_ID, "새여행자-A004"),
                identity(PUBLIC_ID, "새여행자-A005"));

        assertThatThrownBy(() -> rankingService(store, candidates, NOW).update(OTHER_MEMBER_ID, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(candidates.remaining()).isZero();
        assertThat(store.status(OTHER_MEMBER_ID).participating()).isFalse();
    }

    @Test
    void retriesNicknameCollisionWithFreshIdentity() {
        var store = new InMemoryStampStore();
        store.participate(MEMBER_ID, identity(PUBLIC_ID, "기존여행자-A001"), NOW);
        var nextId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        var winningId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        var candidates = new QueueIdentityGenerator(
                identity(nextId, "기존여행자-A001"),
                identity(winningId, "새여행자-A002"));

        var status = rankingService(store, candidates, NOW).update(OTHER_MEMBER_ID, true);

        assertThat(status.publicNickname()).isEqualTo("새여행자-A002");
        assertThat(candidates.remaining()).isZero();
    }

    @Test
    void rejectsLeaderboardLimitsOutsideOneToHundred() {
        var service = rankingService(new InMemoryStampStore(), new QueueIdentityGenerator(), NOW);

        assertThatThrownBy(() -> service.leaderboard(0))
                .isInstanceOf(StampRankingInputInvalidException.class);
        assertThatThrownBy(() -> service.leaderboard(101))
                .isInstanceOf(StampRankingInputInvalidException.class);
        assertThat(service.leaderboard(1).generatedAt()).isEqualTo(NOW);
        assertThat(service.leaderboard(100).entries()).isEmpty();
    }

    @Test
    void repeatedStateUpdatesKeepIdentityAndDoNotTriggerRateLimit() {
        var store = new InMemoryStampStore();
        var candidates = new QueueIdentityGenerator(identity(PUBLIC_ID, "고즈넉한여행자-A7K2"));
        var service = rankingService(store, candidates, NOW);

        var first = service.update(MEMBER_ID, true);
        var repeated = service.update(MEMBER_ID, true);
        assertThat(repeated).isEqualTo(first);
        assertThat(service.leaderboard(20).entries()).hasSize(1);
        assertThat(service.update(MEMBER_ID, false).participating()).isFalse();
        assertThat(service.update(MEMBER_ID, false).participating()).isFalse();
    }

    @Test
    void withdrawalBypassesLimitButRejoiningWithinFiveSecondsIsRejected() {
        var store = new InMemoryStampStore();
        rankingService(store, new QueueIdentityGenerator(identity(PUBLIC_ID, "처음여행자-A001")), NOW)
                .update(MEMBER_ID, true);
        var afterOneSecond = NOW.plusSeconds(1);
        assertThat(rankingService(store, new QueueIdentityGenerator(), afterOneSecond)
                .update(MEMBER_ID, false).participating()).isFalse();

        assertThatThrownBy(() -> rankingService(store,
                new QueueIdentityGenerator(identity(UUID.randomUUID(), "다시여행자-A002")),
                NOW.plusSeconds(5)).update(MEMBER_ID, true))
                .isInstanceOf(StampRankingRateLimitedException.class);
        assertThat(rankingService(store,
                new QueueIdentityGenerator(identity(UUID.randomUUID(), "다시여행자-A003")),
                NOW.plusSeconds(6)).update(MEMBER_ID, true).participating()).isTrue();
    }

    @Test
    void leaderboardUsesAwardSummaryAndAssignsOrdinalRanksBeforeLimit() {
        var store = new InMemoryStampStore();
        var firstPublic = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
        var secondPublic = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        store.participate(MEMBER_ID, identity(firstPublic, "첫째여행자-A001"), NOW);
        store.participate(OTHER_MEMBER_ID, identity(secondPublic, "둘째여행자-A002"), NOW);
        // The real stamp service and award ledger remain authoritative for ranking totals.
        CheckInPlaceLookup lookup = (placeId, latitude, longitude) -> Optional.of(
                new VerifiedPlace(UUID.fromString("20000000-0000-0000-0000-000000000001"),
                        placeId, "kr-11-jongno", 10));
        new StampService(lookup, store, Clock.fixed(NOW, ZoneOffset.UTC)).checkIn(
                MEMBER_ID, new CheckInCommand("p-bukchon", 37.5, 127.0, 10));

        var entries = rankingService(store, new QueueIdentityGenerator(), NOW).leaderboard(1).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().rank()).isEqualTo(1);
        assertThat(entries.getFirst().publicId()).isEqualTo(firstPublic);
        assertThat(entries.getFirst().stampCount()).isEqualTo(store.book(MEMBER_ID).summary().collectedCount());
        assertThat(entries.getFirst().visitedRegionCount())
                .isEqualTo(store.book(MEMBER_ID).summary().visitedRegionCount());
        assertThat(store.status(OTHER_MEMBER_ID).rank()).isEqualTo(2);
        assertThat(store.status(OTHER_MEMBER_ID).participantCount()).isEqualTo(2);
    }

    @Test
    void visitedRegionCountBreaksEqualStampCountBeforeTimeAndPublicId() {
        var store = new InMemoryStampStore();
        var lowerPublicId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        var higherPublicId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        store.participate(MEMBER_ID, identity(lowerPublicId, "서울산책가-A001"), NOW);
        store.participate(OTHER_MEMBER_ID, identity(higherPublicId, "전국산책가-A002"), NOW);
        award(store, MEMBER_ID, "p-bukchon", "kr-11-jongno", NOW);
        award(store, MEMBER_ID, "p-eunpyeong", "kr-11-eunpyeong", NOW);
        award(store, OTHER_MEMBER_ID, "p-bukchon", "kr-11-jongno", NOW.plusSeconds(1));
        award(store, OTHER_MEMBER_ID, "p-suwon", "kr-41-suwon", NOW.plusSeconds(1));

        var entries = store.leaderboard(20);

        assertThat(entries).extracting(StampRankingEntry::publicId)
                .containsExactly(higherPublicId, lowerPublicId);
        assertThat(entries).extracting(StampRankingEntry::stampCount).containsExactly(2, 2);
        assertThat(entries).extracting(StampRankingEntry::visitedRegionCount).containsExactly(2, 1);
        assertThat(entries).extracting(StampRankingEntry::rank).containsExactly(1, 2);
    }

    @Test
    void earlierLastAwardBreaksEqualCountsBeforePublicId() {
        var store = new InMemoryStampStore();
        var earlierPublicId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        var laterPublicId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        store.participate(MEMBER_ID, identity(earlierPublicId, "먼저산책가-A001"), NOW);
        store.participate(OTHER_MEMBER_ID, identity(laterPublicId, "나중산책가-A002"), NOW);
        award(store, MEMBER_ID, "p-bukchon", "kr-11-jongno", NOW);
        award(store, OTHER_MEMBER_ID, "p-bukchon", "kr-11-jongno", NOW.plusSeconds(1));

        var entries = store.leaderboard(20);

        assertThat(entries).extracting(StampRankingEntry::publicId)
                .containsExactly(earlierPublicId, laterPublicId);
        assertThat(entries).extracting(StampRankingEntry::stampCount).containsExactly(1, 1);
        assertThat(entries).extracting(StampRankingEntry::visitedRegionCount).containsExactly(1, 1);
    }

    @Test
    void publicIdBreaksCompletelyEqualRanksWithNoAwards() {
        var store = new InMemoryStampStore();
        var higherPublicId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        var lowerPublicId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        store.participate(MEMBER_ID, identity(higherPublicId, "높은산책가-A001"), NOW);
        store.participate(OTHER_MEMBER_ID, identity(lowerPublicId, "낮은산책가-A002"), NOW);

        var entries = store.leaderboard(20);

        assertThat(entries).extracting(StampRankingEntry::publicId)
                .containsExactly(lowerPublicId, higherPublicId);
        assertThat(entries).extracting(StampRankingEntry::rank).containsExactly(1, 2);
        assertThat(entries).extracting(StampRankingEntry::stampCount).containsExactly(0, 0);
    }

    @Test
    void absentLastAwardSortsAfterPresentLastAwardWhenCountsTie() {
        var absent = new StampRankingSortKey(0, 0, null,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        var present = new StampRankingSortKey(0, 0, NOW,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440002"));

        assertThat(List.of(absent, present).stream().sorted().map(StampRankingSortKey::publicId))
                .containsExactly(present.publicId(), absent.publicId());
    }

    private static void award(InMemoryStampStore store, UUID memberId,
                              String placeId, String regionCode, Instant now) {
        CheckInPlaceLookup lookup = (requestedPlaceId, latitude, longitude) -> Optional.of(
                new VerifiedPlace(UUID.nameUUIDFromBytes(placeId.getBytes(StandardCharsets.UTF_8)),
                        requestedPlaceId, regionCode, 10));
        new StampService(lookup, store, Clock.fixed(now, ZoneOffset.UTC)).checkIn(
                memberId, new CheckInCommand(placeId, 37.5, 127.0, 10));
    }

    private static StampRankingIdentity identity(UUID publicId, String nickname) {
        return StampRankingIdentity.generated(publicId, nickname);
    }

    private static StampRankingService rankingService(
            InMemoryStampStore store, StampRankingIdentityGenerator identities, Instant now) {
        return new StampRankingService(store, identities, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static final class QueueIdentityGenerator implements StampRankingIdentityGenerator {
        private final Queue<StampRankingIdentity> candidates = new ArrayDeque<>();

        private QueueIdentityGenerator(StampRankingIdentity... candidates) {
            this.candidates.addAll(java.util.List.of(candidates));
        }

        @Override
        public StampRankingIdentity generate() {
            return candidates.remove();
        }

        private int remaining() {
            return candidates.size();
        }
    }
}
