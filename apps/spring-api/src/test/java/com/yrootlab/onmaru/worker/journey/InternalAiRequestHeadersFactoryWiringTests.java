package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokResearchPort;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #259 회귀 테스트. JourneyWorkerConfiguration과 ScreenHanokConfiguration이 DataSource와
 * 함께 동시에 활성화되면 서로 다른 token scope(journey.proposal:write / screen-hanok.research:write)를
 * 가지는 InternalAiRequestHeadersFactory Bean 2개가 공존한다. aiProposalClient와 screenHanokResearchPort는
 * 명시적 @Qualifier로 각자의 factory를 받아야 하며, 파라미터 이름 해석에 의존해서는 안 된다.
 */
class InternalAiRequestHeadersFactoryWiringTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SharedDependencies.class,
                    JourneyWorkerConfiguration.class,
                    screenHanokConfiguration())
            .withPropertyValues(
                    "onmaru.ai.base-url=http://localhost:18080",
                    "onmaru.ai.timeout=PT1S");

    @Test
    void wiresJourneyAndScreenHanokClientsWithDistinctHeaderFactoriesWhenBothActive() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiProposalClient.class);
            assertThat(context).hasSingleBean(ScreenHanokResearchPort.class);
            assertThat(context).hasBean("internalAiRequestHeadersFactory");
            assertThat(context).hasBean("screenHanokRequestHeadersFactory");
            assertThat(context.getBean("internalAiRequestHeadersFactory", InternalAiRequestHeadersFactory.class))
                    .isNotSameAs(context.getBean("screenHanokRequestHeadersFactory", InternalAiRequestHeadersFactory.class));
        });
    }

    /**
     * ScreenHanokConfiguration은 다른 패키지의 package-private 클래스이므로 리플렉션으로 로드한다.
     * 운영 컨텍스트와 동일하게 component scan 대상 클래스를 그대로 사용한다.
     */
    private static Class<?> screenHanokConfiguration() {
        try {
            return Class.forName("com.yrootlab.onmaru.web.screenhanok.ScreenHanokConfiguration");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("ScreenHanokConfiguration 클래스를 찾을 수 없다", exception);
        }
    }

    static class SharedDependencies {

        @org.springframework.context.annotation.Bean
        SecretProvider secretProvider() {
            return new FakeSecretProvider();
        }

        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @org.springframework.context.annotation.Bean
        JourneyWorkerTelemetry telemetry() {
            return ignored -> { };
        }

        @org.springframework.context.annotation.Bean
        InMemoryHanokListStore hanokListStore() {
            return new InMemoryHanokListStore();
        }

        @org.springframework.context.annotation.Bean
        HanokSavedStateLookup hanokSavedStateLookup() {
            return (Optional<UUID> memberId, String placeId) -> false;
        }

        @org.springframework.context.annotation.Bean
        DataSource dataSource() {
            return new DataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public PrintWriter getLogWriter() {
                    return null;
                }

                @Override
                public void setLogWriter(PrintWriter out) {
                }

                @Override
                public void setLoginTimeout(int seconds) {
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public <T> T unwrap(Class<T> iface) throws SQLException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }
            };
        }
    }
}
