package com.yrootlab.onmaru.scheduling.retention;

import com.yrootlab.onmaru.observability.InMemoryTelemetrySink;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupResult;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupService;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupStore;
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

class RetentionCleanupConfigurationTests {

    @Test
    void observerRecordsStructuredTelemetryWithoutResourceIds() {
        var sink = new InMemoryTelemetrySink();
        var observer = new TelemetryRetentionCleanupObserver(sink);

        observer.record(new RetentionCleanupResult(1, 2, 3, 4, 5, 6, 21));

        assertThat(sink.events()).hasSize(1);
        assertThat(sink.events().getFirst().name()).isEqualTo("operations.retention.cleanup.completed");
        assertThat(sink.events().getFirst().attributes())
                .containsEntry("expired_guests", "1")
                .containsEntry("expired_sessions", "2")
                .containsEntry("expired_runs", "3")
                .containsEntry("expired_proposals", "4")
                .containsEntry("inactive_revisions", "5")
                .containsEntry("member_deletion_resources", "6")
                .containsEntry("ledger_entries", "21")
                .doesNotContainKeys("resource_id", "member_id");
    }

    @Test
    void wiresCleanupStoreServiceAndJobWhenDataSourceExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestDependencies.class, RetentionCleanupConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RetentionCleanupStore.class);
                    assertThat(context).hasSingleBean(RetentionCleanupService.class);
                    assertThat(context).hasSingleBean(RetentionCleanupJob.class);
                    assertThat(context).hasSingleBean(TelemetryRetentionCleanupObserver.class);
                });
    }

    static class TestDependencies {

        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @org.springframework.context.annotation.Bean
        InMemoryTelemetrySink telemetrySink() {
            return new InMemoryTelemetrySink();
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
