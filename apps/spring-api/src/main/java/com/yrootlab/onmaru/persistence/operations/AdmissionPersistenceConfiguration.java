package com.yrootlab.onmaru.persistence.operations;

import com.yrootlab.onmaru.operations.admission.AdmissionStore;
import com.yrootlab.onmaru.persistence.operations.admission.JdbcAdmissionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class AdmissionPersistenceConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AdmissionStore.class)
    AdmissionStore jdbcAdmissionStore(DataSource dataSource) {
        return new JdbcAdmissionStore(dataSource);
    }
}
