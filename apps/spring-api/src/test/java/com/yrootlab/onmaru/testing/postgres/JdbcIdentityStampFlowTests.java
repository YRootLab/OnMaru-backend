package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.identity.oauth.CompleteOAuthLoginCommand;
import com.yrootlab.onmaru.identity.oauth.ExternalIdentity;
import com.yrootlab.onmaru.identity.oauth.OAuthLoginService;
import com.yrootlab.onmaru.identity.oauth.OAuthProvider;
import com.yrootlab.onmaru.identity.oauth.StartOAuthLoginCommand;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
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

import static org.assertj.core.api.Assertions.assertThat;

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
        var login = new OAuthLoginService(identityStore, hasher, Clock.fixed(NOW, ZoneOffset.UTC));
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
                .get().extracting("id").isEqualTo(result.memberId());
        assertThat(checkedIn.newAwards()).extracting("code").containsExactly("stamp_bukchon");
        assertThat(status.participating()).isTrue();
        assertThat(rankings.leaderboard(20)).extracting("publicNickname")
                .containsExactly("익명 유람객 0262");
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
