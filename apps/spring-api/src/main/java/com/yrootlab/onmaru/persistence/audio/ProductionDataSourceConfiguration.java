package com.yrootlab.onmaru.persistence.audio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

@Configuration
@Profile("production")
@ConditionalOnProperty(name = "spring.datasource.url")
@EnableConfigurationProperties(ProductionDataSourceConfiguration.DatabaseSettings.class)
class ProductionDataSourceConfiguration {

    @Bean
    DataSource productionDataSource(DatabaseSettings settings) {
        return new DriverManagerDataSource(
                required(settings.url(), "spring.datasource.url"),
                required(settings.username(), "spring.datasource.username"),
                required(settings.password(), "spring.datasource.password"));
    }

    private String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required in the production profile");
        }
        return value;
    }

    @ConfigurationProperties("spring.datasource")
    record DatabaseSettings(String url, String username, String password) {
    }

    private record DriverManagerDataSource(
            String url,
            String username,
            String password
    ) implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public Connection getConnection(String suppliedUsername, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(url, suppliedUsername, suppliedPassword);
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { DriverManager.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
