package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampRankingStore;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampStore;
import com.yrootlab.onmaru.stamp.ranking.StampRankingEntry;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentity;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentityConflictException;
import com.yrootlab.onmaru.stamp.ranking.StampRankingRateLimitedException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStampRankingStoreTests {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    private static final Instant NOW = Instant.parse("2026-09-27T02:30:00Z");
    private DataSource dataSource;
    private JdbcStampRankingStore store;

    @BeforeAll
    static void startPostgres() { POSTGRES.start(); }

    @AfterAll
    static void stopPostgres() { POSTGRES.stop(); }

    @BeforeEach
    void resetDatabase() throws Exception {
        var url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
        dataSource = new DriverManagerDataSource(url, "onmaru_test", "onmaru_test");
        try (var connection = dataSource.getConnection()) { PostgresTestDatabase.reset(connection); }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        store = new JdbcStampRankingStore(dataSource);
    }

    @Test
    void excludesNonParticipantsAndDeletingMembersAndMatchesActiveStampBook() throws Exception {
        var active = member();
        var privateMember = member();
        var deleting = member();
        participate(active, 1);
        participate(deleting, 2);
        sql("UPDATE onmaru.identity_members SET status = 'DELETING' WHERE id = ?", deleting);
        award(active, "stamp_bukchon", NOW);
        award(active, "stamp_eunpyeong", NOW);
        award(active, "stamp_night_hanok", NOW);
        award(active, "stamp_jeonju", NOW.plusSeconds(100));
        award(privateMember, "stamp_bukchon", NOW);
        award(deleting, "stamp_bukchon", NOW);
        sql("UPDATE onmaru.stamp_definitions SET active = false WHERE code = 'stamp_jeonju'");

        var entries = store.leaderboard(20);
        assertThat(entries).extracting(StampRankingEntry::publicNickname).containsExactly("달빛여행자-A001");
        var summary = new JdbcStampStore(dataSource).book(active).summary();
        assertThat(entries.getFirst().stampCount()).isEqualTo(3).isEqualTo(summary.collectedCount());
        assertThat(entries.getFirst().visitedRegionCount()).isEqualTo(1).isEqualTo(summary.visitedRegionCount());
        assertThat(entries.getFirst().completionRate()).isEqualTo(27).isEqualTo(summary.completionRate());
        assertThat(store.status(active).participantCount()).isOne();
        var privateStatus = store.status(privateMember);
        assertThat(privateStatus.participating()).isFalse();
        assertThat(privateStatus.rank()).isNull();
        assertThat(privateStatus.stampCount()).isOne();
        assertThat(privateStatus.visitedRegionCount()).isOne();
        assertThat(privateStatus.publicNickname()).isNull();
        assertThat(store.status(deleting).rank()).isNull();
    }

    @Test
    void ordersAllFourKeysAndComputesPersonalRankBeforeLimit() throws Exception {
        var members = new ArrayList<UUID>();
        for (int i = 1; i <= 7; i++) {
            var id = member();
            members.add(id);
            participate(id, i);
        }
        // Reverse public IDs against the score, region and time preferences.
        award(members.get(6), "stamp_bukchon", NOW);
        award(members.get(6), "stamp_eunpyeong", NOW);
        award(members.get(6), "stamp_night_hanok", NOW);
        award(members.get(5), "stamp_bukchon", NOW.plusSeconds(10));
        award(members.get(5), "stamp_jeonju", NOW.plusSeconds(10));
        award(members.get(4), "stamp_bukchon", NOW);
        award(members.get(4), "stamp_eunpyeong", NOW);
        award(members.get(3), "stamp_bukchon", NOW.plusSeconds(10));
        award(members.get(3), "stamp_eunpyeong", NOW.plusSeconds(10));
        // Inactive awards must not influence count or lastAwardedAt.
        award(members.get(4), "stamp_oeam", NOW.plusSeconds(100));
        sql("UPDATE onmaru.stamp_definitions SET active = false WHERE code = 'stamp_oeam'");

        assertThat(store.leaderboard(20)).extracting(StampRankingEntry::publicId)
                .containsExactly(publicId(7), publicId(6), publicId(5), publicId(4), publicId(1), publicId(2), publicId(3));
        assertThat(store.leaderboard(2)).extracting(StampRankingEntry::rank).containsExactly(1, 2);
        assertThat(store.status(members.get(2)).rank()).isEqualTo(7);
        assertThat(store.status(members.get(2)).participantCount()).isEqualTo(7);
    }

    @Test
    void withdrawsImmediatelyClearsIdentityAndRotatesAfterFiveSeconds() throws Exception {
        var member = member();
        participate(member, 1);
        award(member, "stamp_bukchon", NOW);
        var withdrawn = store.withdraw(member, NOW.plusMillis(100));
        assertThat(withdrawn.participating()).isFalse();
        assertThat(withdrawn.publicNickname()).isNull();
        assertThat(withdrawn.nicknameType()).isNull();
        assertThat(withdrawn.rank()).isNull();
        assertThat(withdrawn.stampCount()).isOne();
        assertThat(store.leaderboard(20)).isEmpty();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT ranking_public_id, nickname_normalized FROM onmaru.stamp_ranking_profiles")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject(1)).isNull();
            assertThat(result.getString(2)).isNull();
        }
        assertThat(store.withdraw(member, NOW.plusSeconds(4))).isEqualTo(withdrawn);
        assertThatThrownBy(() -> store.participate(member, identity(2), NOW.plusMillis(5000)))
                .isInstanceOfSatisfying(StampRankingRateLimitedException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isOne());
        store.participate(member, identity(2), NOW.plusMillis(5100));
        assertThat(store.leaderboard(20).getFirst().publicId()).isEqualTo(publicId(2));
    }

    @Test
    void sameStateRequestsDoNotChangeIdentityOrCreateWithdrawalCooldown() throws Exception {
        var member = member();
        assertThat(store.withdraw(member, NOW).participating()).isFalse();
        var first = store.participate(member, identity(1), NOW);
        assertThat(store.participate(member, identity(2), NOW.plusMillis(1))).isEqualTo(first);
        assertThat(store.leaderboard(20).getFirst().publicId()).isEqualTo(publicId(1));
    }

    @Test
    void serializesConcurrentFirstParticipationEvenWithoutProfileRow() throws Exception {
        var member = member();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return store.participate(member, identity(1), NOW); });
            var second = executor.submit(() -> { start.await(); return store.participate(member, identity(2), NOW); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(store.leaderboard(20)).hasSize(1);
    }

    @Test
    void mapsOnlyPublicIdentityUniqueViolationsAndLeavesFailedMemberUnchanged() throws Exception {
        var first = member();
        var second = member();
        participate(first, 1);
        assertThatThrownBy(() -> store.participate(second,
                StampRankingIdentity.generated(publicId(1), "다른여행자-A002"), NOW))
                .isInstanceOf(StampRankingIdentityConflictException.class);
        assertThatThrownBy(() -> store.participate(second,
                StampRankingIdentity.generated(publicId(2), "달빛여행자-A001"), NOW))
                .isInstanceOf(StampRankingIdentityConflictException.class);
        assertThat(store.status(second).participating()).isFalse();
        assertThatThrownBy(() -> store.participate(UUID.randomUUID(), identity(3), NOW))
                .isInstanceOf(IllegalStateException.class);
        participate(second, 2);
        assertThat(store.leaderboard(20)).hasSize(2);
    }

    @Test
    void seesNewAwardsAndHandlesZeroActiveDefinitionsWithoutPersistedScores() throws Exception {
        var member = member();
        participate(member, 1);
        assertThat(store.leaderboard(20).getFirst().stampCount()).isZero();
        award(member, "stamp_bukchon", NOW);
        assertThat(store.leaderboard(20).getFirst().stampCount()).isOne();
        assertThat(store.status(member).completionRate()).isEqualTo(8);
        sql("UPDATE onmaru.stamp_definitions SET active = false");
        assertThat(store.leaderboard(20).getFirst().completionRate()).isZero();
        assertThat(store.status(member).stampCount()).isZero();
        assertThat(store.status(member).visitedRegionCount()).isZero();
    }

    @Test
    void sharesOuterTransactionAndRecoversIdentityCollisionForRetry() throws Exception {
        var first = member();
        var second = member();
        participate(first, 1);
        var transactions = new JdbcTransactionRunner(dataSource);
        var transactional = new JdbcStampRankingStore(dataSource, transactions);
        transactions.execute(connection -> {
            assertThatThrownBy(() -> transactional.participate(second, identity(1), NOW))
                    .isInstanceOf(StampRankingIdentityConflictException.class);
            return transactional.participate(second, identity(2), NOW);
        });
        assertThat(store.status(second).participating()).isTrue();
        assertThatThrownBy(() -> transactions.execute(connection -> {
            transactional.withdraw(second, NOW.plusSeconds(1));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.status(second).participating()).isTrue();
    }

    @Test
    void fetchesWholeLeaderboardAndStatusInOneQueryAndCanUseMemberLeadingIndexes() throws Exception {
        var first = member();
        participate(first, 1);
        participate(member(), 2);
        award(first, "stamp_bukchon", NOW);
        var queries = new ArrayList<String>();
        var observed = new JdbcStampRankingStore(recordingDataSource(queries));
        assertThat(observed.leaderboard(20)).hasSize(2);
        assertThat(queries).hasSize(1);
        var leaderboardSql = queries.getFirst();
        queries.clear();
        assertThat(observed.status(first).participantCount()).isEqualTo(2);
        assertThat(queries).hasSize(1);
        try (var connection = dataSource.getConnection(); var settings = connection.createStatement()) {
            // Tiny fixtures otherwise favor sequential scans; this checks index eligibility, not latency.
            settings.execute("SET enable_seqscan = off");
            try (var explain = connection.prepareStatement("EXPLAIN (FORMAT JSON) " + leaderboardSql)) {
                explain.setInt(1, 20);
                try (var result = explain.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    var plan = new ObjectMapper().readTree(result.getString(1));
                    assertThat(plan.findValuesAsText("Relation Name"))
                            .contains("stamp_ranking_profiles", "stamp_awards");
                    // All three indexes have the same participating predicate; the planner may pick any.
                    assertThat(plan.findValuesAsText("Index Name")).containsAnyOf(
                            "stamp_ranking_profiles_participating_idx",
                            "stamp_ranking_profiles_public_id_uq", "stamp_ranking_profiles_nickname_uq");
                    assertThat(plan.findValuesAsText("Index Name")).containsAnyOf(
                            "stamp_awards_member_awarded_idx", "stamp_awards_member_stamp_uq");
                    assertThat(plan.findValuesAsText("Index Cond"))
                            .anyMatch(condition -> condition.contains("member_id"));
                }
            }
        }
    }

    private DataSource recordingDataSource(ArrayList<String> queries) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        var value = method.invoke(dataSource, args);
                        if (value instanceof Connection connection) {
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                                    (connectionProxy, connectionMethod, connectionArgs) -> {
                                        if (connectionMethod.getName().equals("prepareStatement")) {
                                            queries.add((String) connectionArgs[0]);
                                        }
                                        try { return connectionMethod.invoke(connection, connectionArgs); }
                                        catch (InvocationTargetException exception) { throw exception.getCause(); }
                                    });
                        }
                        return value;
                    } catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
    }

    private UUID member() throws Exception {
        var member = UUID.randomUUID();
        sql("INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', ?)", member, atUtc(NOW));
        return member;
    }

    private void participate(UUID member, int number) { store.participate(member, identity(number), NOW); }

    private static StampRankingIdentity identity(int number) {
        return StampRankingIdentity.generated(publicId(number), "달빛여행자-A" + String.format("%03d", number));
    }

    private static UUID publicId(int number) { return new UUID(0, number); }

    private void award(UUID member, String code, Instant time) throws Exception {
        var place = UUID.randomUUID();
        var checkIn = UUID.randomUUID();
        sql("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)", place, atUtc(time));
        sql("""
                INSERT INTO onmaru.stamp_check_ins
                    (id, member_id, place_id, public_place_id, region_code, checked_in_at,
                     check_in_bucket, distance_meters, accuracy_meters)
                VALUES (?, ?, ?, 'p-ranking-test', 'kr-11-jongno', ?,
                        date_bin('15 minutes', ?::timestamptz, '2000-01-01'::timestamptz), 10, 10)
                """, checkIn, member, place, atUtc(time), atUtc(time));
        sql("INSERT INTO onmaru.stamp_awards (id, member_id, stamp_code, trigger_check_in_id, awarded_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), member, code, checkIn, atUtc(time));
    }

    private void sql(String sql, Object... parameters) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            statement.executeUpdate();
        }
    }

    private static OffsetDateTime atUtc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
}
