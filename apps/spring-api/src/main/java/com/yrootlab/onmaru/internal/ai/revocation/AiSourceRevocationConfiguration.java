package com.yrootlab.onmaru.internal.ai.revocation;

import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
class AiSourceRevocationConfiguration {

    @Bean
    JdbcAiSourceRevocationStore jdbcAiSourceRevocationStore(DataSource dataSource) {
        return new JdbcAiSourceRevocationStore(dataSource);
    }
}
