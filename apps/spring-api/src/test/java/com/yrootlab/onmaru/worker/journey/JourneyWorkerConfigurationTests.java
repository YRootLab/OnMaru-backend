package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.worker.BaselinePlanner;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerService;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyWorkerConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JourneyWorkerConfiguration.class, TestDependencies.class)
            .withPropertyValues(
                    "onmaru.ai.base-url=http://localhost:18080",
                    "onmaru.ai.timeout=PT1S");

    @Test
    void wiresWorkerRunnerServiceAdaptersAndControllableInMemoryProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JourneyWorkerRunner.class);
            assertThat(context).hasSingleBean(JourneyWorkerService.class);
            assertThat(context).hasSingleBean(JourneyWorkerQueue.class);
            assertThat(context).hasSingleBean(InMemoryJourneyCandidateProvider.class);
            assertThat(context).hasSingleBean(BaselinePlanner.class);
            assertThat(context).hasSingleBean(JourneyRunStore.class);
            assertThat(context).hasSingleBean(JourneyResultStore.class);
        });
    }

    static class TestDependencies {

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
