package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.identity.oauth.CompleteOAuthLoginCommand;
import com.yrootlab.onmaru.identity.oauth.ExternalIdentity;
import com.yrootlab.onmaru.identity.oauth.OAuthLoginService;
import com.yrootlab.onmaru.identity.oauth.OAuthProvider;
import com.yrootlab.onmaru.identity.oauth.StartOAuthLoginCommand;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.identity.profile.MemberProfileGenerator;
import com.yrootlab.onmaru.identity.profile.MemberProfilePatch;
import com.yrootlab.onmaru.identity.profile.MemberProfileService;
import com.yrootlab.onmaru.persistence.identity.JdbcIdentityStore;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampRankingStore;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampStore;
import com.yrootlab.onmaru.stamp.VerifiedPlace;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentity;
import com.yrootlab.onmaru.stamp.ranking.StampRankingNicknameType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIdentityStampFlowTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    private static final Instant NOW = Instant.parse("2026-09-27T03:00:00Z");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void oauthLoginCreatesCanonicalMemberUsedByCheckInAndRankingWithoutMemberPreseed() throws Exception {
        resetAndMigrate();
        var dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
        var identityStore = new JdbcIdentityStore(dataSource);
        var hasher = new TokenHasher("integration-pepper");
        var login = new OAuthLoginService(
                identityStore,
                hasher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new MemberProfileGenerator(bound -> bound == 10_000 ? 552 : 0));
        var provider = new OAuthProvider("KAKAO", "https://kauth.kakao.com");
        var started = login.startLogin(new StartOAuthLoginCommand(
                provider, "browser-nonce", "pkce-verifier", null, "/stamps"));

        var result = login.completeLogin(new CompleteOAuthLoginCommand(
                provider, started.state(), "browser-nonce", "pkce-verifier",
                new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "kakao-user-262")));

        var placeId = seedPlace();
        var stamps = new JdbcStampStore(dataSource);
        var checkedIn = stamps.record(result.memberId(),
                new VerifiedPlace(placeId, "p-production-flow", "kr-11-jongno", 12), NOW, 10);
        var rankings = new JdbcStampRankingStore(dataSource);
        var status = rankings.participate(result.memberId(), new StampRankingIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000262"),
                "익명 유람객 0262", "익명 유람객 0262", StampRankingNicknameType.GENERATED), NOW);

        assertThat(identityStore.findActiveMemberBySessionHash(hasher.hash(result.sessionToken()), NOW))
                .get().extracting("id", "displayName", "characterId", "backgroundId")
                .containsExactly(result.memberId(), "고요한 마루 0552", "CHARACTER_01", "BACKGROUND_01");
        assertThat(profileCount()).isEqualTo(1);

        new MemberProfileService(identityStore).updateActiveProfile(
                result.memberId(),
                new MemberProfilePatch("바꾼 이름", "CHARACTER_10", "BACKGROUND_10"),
                NOW.plusSeconds(60)).orElseThrow();
        var secondStarted = login.startLogin(new StartOAuthLoginCommand(
                provider, "browser-nonce-2", "pkce-verifier-2", null, "/stamps"));
        var second = login.completeLogin(new CompleteOAuthLoginCommand(
                provider, secondStarted.state(), "browser-nonce-2", "pkce-verifier-2",
                new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "kakao-user-262")));

        assertThat(second.memberId()).isEqualTo(result.memberId());
        assertThat(identityStore.findByMemberId(result.memberId()).orElseThrow())
                .extracting("displayName", "characterId", "backgroundId")
                .containsExactly(
                        "바꾼 이름",
                        com.yrootlab.onmaru.identity.profile.MemberProfileCharacter.CHARACTER_10,
                        com.yrootlab.onmaru.identity.profile.MemberProfileBackground.BACKGROUND_10);
        assertThat(profileCount()).isEqualTo(1);
        assertThat(checkedIn.newAwards()).extracting("code").containsExactly("stamp_bukchon");
        assertThat(status.participating()).isTrue();
        assertThat(rankings.leaderboard(20)).extracting("publicNickname")
                .containsExactly("익명 유람객 0262");
    }

    @Test
    void concurrentFirstOAuthCallbacksCreateOneMemberAccountAndProfile() throws Exception {
        resetAndMigrate();
        var dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
        var store = new JdbcIdentityStore(dataSource);
        var service = new OAuthLoginService(
                store, new TokenHasher("integration-pepper"), Clock.fixed(NOW, ZoneOffset.UTC),
                new MemberProfileGenerator(bound -> 0));
        var provider = new OAuthProvider("KAKAO", "https://kauth.kakao.com");
        var first = service.startLogin(new StartOAuthLoginCommand(provider, "nonce-1", "pkce-1", null, "/"));
        var second = service.startLogin(new StartOAuthLoginCommand(provider, "nonce-2", "pkce-2", null, "/"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.completeLogin(new CompleteOAuthLoginCommand(
                        provider, first.state(), "nonce-1", "pkce-1",
                        new ExternalIdentity("KAKAO", provider.issuer(), "same-user"))).memberId();
            });
            var secondFuture = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.completeLogin(new CompleteOAuthLoginCommand(
                        provider, second.state(), "nonce-2", "pkce-2",
                        new ExternalIdentity("KAKAO", provider.issuer(), "same-user"))).memberId();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(firstFuture.get(10, TimeUnit.SECONDS))
                    .isEqualTo(secondFuture.get(10, TimeUnit.SECONDS));
        }

        assertThat(rowCount("onmaru.identity_members")).isEqualTo(1);
        assertThat(rowCount("onmaru.identity_external_accounts")).isEqualTo(1);
        assertThat(rowCount("onmaru.identity_member_profiles")).isEqualTo(1);
    }

    @Test
    void loginAfterDeletionCreatesFreshMemberWithoutRestoringOldSessionOrProfile() throws Exception {
        resetAndMigrate();
        var store = new JdbcIdentityStore(new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test"));
        var hasher = new TokenHasher("integration-pepper");
        var service = new OAuthLoginService(store, hasher, Clock.fixed(NOW, ZoneOffset.UTC),
                new MemberProfileGenerator(bound -> 0));
        var provider = new OAuthProvider("KAKAO", "https://kauth.kakao.com");
        var identity = new ExternalIdentity("KAKAO", provider.issuer(), "rejoin-user");
        var firstState = service.startLogin(new StartOAuthLoginCommand(provider, "nonce-1", "pkce-1", null, "/"));
        var first = service.completeLogin(new CompleteOAuthLoginCommand(
                provider, firstState.state(), "nonce-1", "pkce-1", identity));
        new MemberProfileService(store).updateActiveProfile(first.memberId(),
                new MemberProfilePatch("이전 이름", null, null), NOW.plusSeconds(1)).orElseThrow();

        assertThat(store.requestDeletion(hasher.hash(first.sessionToken()), NOW.plusSeconds(2))).isPresent();
        var secondState = service.startLogin(new StartOAuthLoginCommand(provider, "nonce-2", "pkce-2", null, "/"));
        var second = service.completeLogin(new CompleteOAuthLoginCommand(
                provider, secondState.state(), "nonce-2", "pkce-2", identity));

        assertThat(second.memberId()).isNotEqualTo(first.memberId());
        assertThat(store.findActiveMemberBySessionHash(hasher.hash(first.sessionToken()), NOW)).isEmpty();
        assertThat(store.findActiveMemberBySessionHash(hasher.hash(second.sessionToken()), NOW))
                .get().extracting("id", "displayName").containsExactly(second.memberId(), "고요한 마루 0000");
        assertThat(store.findByMemberId(first.memberId()).orElseThrow().displayName()).isEqualTo("이전 이름");
        assertThat(rowCount("onmaru.identity_members")).isEqualTo(2);
        assertThat(rowCount("onmaru.identity_external_accounts")).isEqualTo(1);
    }

    @Test
    void missingProfileForActiveSessionRaisesInvariantFailure() throws Exception {
        resetAndMigrate();
        var dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
        var store = new JdbcIdentityStore(dataSource);
        var hasher = new TokenHasher("integration-pepper");
        var service = new OAuthLoginService(
                store, hasher, Clock.fixed(NOW, ZoneOffset.UTC), new MemberProfileGenerator(bound -> 0));
        var provider = new OAuthProvider("KAKAO", "https://kauth.kakao.com");
        var started = service.startLogin(new StartOAuthLoginCommand(provider, "nonce", "pkce", null, "/"));
        var login = service.completeLogin(new CompleteOAuthLoginCommand(
                provider, started.state(), "nonce", "pkce",
                new ExternalIdentity("KAKAO", provider.issuer(), "missing-profile")));
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.prepareStatement(
                     "DELETE FROM onmaru.identity_member_profiles WHERE member_id = ?")) {
            statement.setObject(1, login.memberId());
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> store.findActiveMemberBySessionHash(hasher.hash(login.sessionToken()), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile is missing");
    }

    @Test
    void concurrentPartialProfileUpdatesPreserveBothFields() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO onmaru.identity_members (id, status, created_at) VALUES ('"
                    + memberId + "', 'ACTIVE', '2026-09-27T03:00:00Z')");
            statement.executeUpdate("INSERT INTO onmaru.identity_member_profiles "
                    + "(member_id, display_name, character_id, background_id, created_at, updated_at) VALUES ('"
                    + memberId + "', '고요한 마루 0001', 'CHARACTER_01', 'BACKGROUND_01', "
                    + "'2026-09-27T03:00:00Z', '2026-09-27T03:00:00Z')");
        }
        var service = new MemberProfileService(new JdbcIdentityStore(
                new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test")));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var name = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.updateActiveProfile(memberId, new MemberProfilePatch(
                        "따뜻한 온니 2026", null, null), NOW.plusSeconds(1)).orElseThrow();
            });
            var character = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.updateActiveProfile(memberId, new MemberProfilePatch(
                        null, "CHARACTER_10", null), NOW.plusSeconds(1)).orElseThrow();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            name.get(10, TimeUnit.SECONDS);
            character.get(10, TimeUnit.SECONDS);
        }

        assertThat(service.findByMemberIds(java.util.Set.of(memberId)).get(memberId))
                .extracting("displayName", "characterId", "backgroundId")
                .containsExactly("따뜻한 온니 2026",
                        com.yrootlab.onmaru.identity.profile.MemberProfileCharacter.CHARACTER_10,
                        com.yrootlab.onmaru.identity.profile.MemberProfileBackground.BACKGROUND_01);
    }

    private static UUID seedPlace() throws Exception {
        var placeId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)
                     """)) {
            statement.setObject(1, placeId);
            statement.setObject(2, NOW.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
        return placeId;
    }

    private static int profileCount() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM onmaru.identity_member_profiles")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int rowCount(String table) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void resetAndMigrate() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure().dataSource(jdbcUrl(), "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}
