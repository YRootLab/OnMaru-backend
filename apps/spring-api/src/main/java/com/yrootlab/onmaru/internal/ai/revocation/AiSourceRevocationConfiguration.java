package com.yrootlab.onmaru.internal.ai.revocation;

import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
class AiSourceRevocationConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    JdbcAiSourceRevocationStore jdbcAiSourceRevocationStore(DataSource dataSource) {
        return new JdbcAiSourceRevocationStore(dataSource);
    }
}
