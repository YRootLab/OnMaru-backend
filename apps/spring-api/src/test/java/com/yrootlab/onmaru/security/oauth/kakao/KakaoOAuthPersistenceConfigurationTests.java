package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStore;
import com.yrootlab.onmaru.identity.oauth.IdentityStore;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.persistence.identity.JdbcIdentityStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoOAuthPersistenceConfigurationTests {

    @Test
    void productionUsesOneJdbcIdentityLedgerForLoginAndMemberLifecycle() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .withBean(javax.sql.DataSource.class, () -> new DriverManagerDataSource("jdbc:invalid"))
                .withBean(SecretProvider.class, () -> name ->
                        new SecretBundle(name, "configuration-test-secret", Optional.empty()))
                .withUserConfiguration(KakaoOAuthConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(InMemoryIdentityStore.class);
                    assertThat(context.getBean(IdentityStore.class)).isInstanceOf(JdbcIdentityStore.class);
                    assertThat(context.getBean(MemberLifecycleStore.class))
                            .isSameAs(context.getBean(IdentityStore.class));
                });
    }

    @Test
    void localProfileKeepsExplicitInMemoryIdentityAdapter() {
        new ApplicationContextRunner()
                .withBean(SecretProvider.class, () -> name ->
                        new SecretBundle(name, "configuration-test-secret", Optional.empty()))
                .withUserConfiguration(KakaoOAuthConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InMemoryIdentityStore.class);
                    assertThat(context.getBean(IdentityStore.class))
                            .isSameAs(context.getBean(MemberLifecycleStore.class));
                });
    }
}
