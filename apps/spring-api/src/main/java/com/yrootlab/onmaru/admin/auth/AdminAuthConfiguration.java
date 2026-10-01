package com.yrootlab.onmaru.admin.auth;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminAccountStore;
import com.yrootlab.onmaru.admin.users.AdminMemberStore;
import com.yrootlab.onmaru.admin.users.InMemoryAdminMemberStore;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminMemberStore;
import com.yrootlab.onmaru.admin.users.AdminSanctionService;
import com.yrootlab.onmaru.admin.users.AdminSanctionStore;
import com.yrootlab.onmaru.admin.users.InMemoryAdminSanctionStore;
import com.yrootlab.onmaru.admin.users.AdminSanctionMemberAccessPolicy;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminSanctionStore;
import com.yrootlab.onmaru.identity.lifecycle.MemberAccessPolicy;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStore;
import com.yrootlab.onmaru.admin.curation.AdminCurationStore;
import com.yrootlab.onmaru.admin.curation.InMemoryAdminCurationStore;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminCurationStore;
import com.yrootlab.onmaru.admin.audit.AdminAuditLogService;
import com.yrootlab.onmaru.admin.audit.AdminAuditLogStore;
import com.yrootlab.onmaru.admin.audit.InMemoryAdminAuditLogStore;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminAuditLogStore;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
import com.yrootlab.onmaru.admin.pipeline.InMemoryAdminPipelinePort;
import com.yrootlab.onmaru.admin.pipeline.TourApiAdminPipelinePort;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminPipelinePort;
import com.yrootlab.onmaru.tourism.catalog.TourApiCatalogSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminAuthConfiguration {

    @Bean
    @Profile("!production")
    AdminAccountStore inMemoryAdminAccountStore() {
        return new InMemoryAdminAccountStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    AdminAccountStore jdbcAdminAccountStore(DataSource dataSource) {
        return new JdbcAdminAccountStore(dataSource);
    }

    @Bean
    AdminJwtTokenCodec adminJwtTokenCodec(
            SecretProvider secrets, Clock clock, AdminJtiRevocationStore revokedJtis) {
        return new AdminJwtTokenCodec(
                secrets,
                "admin.jwt-signing-key",
                "onmaru-admin",
                "onmaru-admin-web",
                Duration.ofMinutes(15),
                clock, revokedJtis);
    }

    @Bean
    AdminJtiRevocationStore adminJtiRevocationStore() {
        return new InMemoryAdminJtiRevocationStore();
    }

    @Bean
    AdminTokenRevocationService adminTokenRevocationService(
            AdminJtiRevocationStore store, Clock clock) {
        return new AdminTokenRevocationService(store, clock);
    }

    @Bean
    @Profile("!production")
    AdminAuditLogStore inMemoryAdminAuditLogStore() {
        return new InMemoryAdminAuditLogStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    AdminAuditLogStore jdbcAdminAuditLogStore(DataSource dataSource, ObjectMapper mapper) {
        return new JdbcAdminAuditLogStore(dataSource, mapper);
    }

    @Bean
    AdminAuditLogService adminAuditLogService(AdminAuditLogStore store, Clock clock) {
        return new AdminAuditLogService(store, clock);
    }

    @Bean
    @Profile("!production")
    AdminSessionStore inMemoryAdminSessionStore() {
        return new InMemoryAdminSessionStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    AdminSessionStore jdbcAdminSessionStore(DataSource dataSource) {
        return new com.yrootlab.onmaru.persistence.admin.JdbcAdminSessionStore(dataSource);
    }

    @Bean
    AdminSessionService adminSessionService(
            AdminAccountStore accounts,
            AdminSessionStore sessions,
            AdminJwtTokenCodec tokenCodec,
            Clock clock) {
        return new AdminSessionService(accounts, sessions, tokenCodec, clock);
    }

    @Bean
    @Profile("!production")
    AdminMemberStore inMemoryAdminMemberStore() {
        return new InMemoryAdminMemberStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    AdminMemberStore jdbcAdminMemberStore(DataSource dataSource) {
        return new JdbcAdminMemberStore(dataSource);
    }

    @Bean
    @Profile("!production")
    AdminSanctionStore inMemoryAdminSanctionStore() {
        return new InMemoryAdminSanctionStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    AdminSanctionStore jdbcAdminSanctionStore(DataSource dataSource) {
        return new JdbcAdminSanctionStore(dataSource);
    }

    @Bean
    AdminSanctionService adminSanctionService(
            AdminSanctionStore store, Clock clock, MemberLifecycleStore memberSessions) {
        return new AdminSanctionService(store, clock, memberSessions);
    }

    @Bean
    MemberAccessPolicy memberAccessPolicy(AdminSanctionStore store, Clock clock) {
        return new AdminSanctionMemberAccessPolicy(store, clock);
    }

    @Bean
    @Profile("!production")
    AdminCurationStore inMemoryAdminCurationStore() {
        return new InMemoryAdminCurationStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    AdminCurationStore jdbcAdminCurationStore(DataSource dataSource, ObjectMapper mapper) {
        return new JdbcAdminCurationStore(dataSource, mapper);
    }

    @Bean
    @Profile("!production")
    AdminPipelinePort inMemoryAdminPipelinePort() {
        return new InMemoryAdminPipelinePort();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean({DataSource.class, TourApiCatalogSyncService.class})
    AdminPipelinePort tourApiAdminPipelinePort(DataSource dataSource, TourApiCatalogSyncService syncService) {
        return new TourApiAdminPipelinePort(new JdbcAdminPipelinePort(dataSource), syncService);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AdminPipelinePort.class)
    AdminPipelinePort jdbcOnlyAdminPipelinePort(DataSource dataSource) {
        return new JdbcAdminPipelinePort(dataSource);
    }
}
