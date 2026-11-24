package com.yrootlab.onmaru.internal.ai.revocation;

import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
class AiSourceRevocationConfiguration {

    @Bean
    JdbcAiSourceRevocationStore jdbcAiSourceRevocationStore(ObjectProvider<DataSource> dataSources) {
        // Resolve after configuration definitions exist; parse-time conditions miss later JDBC beans.
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? null : new JdbcAiSourceRevocationStore(dataSource);
    }
}
