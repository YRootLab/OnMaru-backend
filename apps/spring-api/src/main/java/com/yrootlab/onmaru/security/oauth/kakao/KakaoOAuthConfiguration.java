package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStore;
import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import com.yrootlab.onmaru.identity.guest.GuestGrantService;
import com.yrootlab.onmaru.identity.guest.InMemoryGuestOwnershipStore;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.IdentityStore;
import com.yrootlab.onmaru.identity.oauth.OAuthLoginService;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.persistence.identity.JdbcIdentityStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties.class)
public class KakaoOAuthConfiguration {

    @Bean
    @Profile("!production")
    InMemoryIdentityStore identityStore() {
        return new InMemoryIdentityStore();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    JdbcIdentityStore jdbcIdentityStore(DataSource dataSource) {
        return new JdbcIdentityStore(dataSource);
    }

    @Bean
    OAuthLoginService oauthLoginService(IdentityStore store, SecretProvider secretProvider, Clock clock) {
        return new OAuthLoginService(
                store,
                new TokenHasher(secretProvider.get("oauth.client-secret").current()),
                clock);
    }

    @Bean
    MemberLifecycleService memberLifecycleService(
            MemberLifecycleStore store,
            SecretProvider secretProvider,
            Clock clock) {
        return new MemberLifecycleService(
                store,
                new TokenHasher(secretProvider.get("oauth.client-secret").current()),
                clock);
    }

    @Bean
    InMemoryGuestOwnershipStore guestOwnershipStore() {
        return new InMemoryGuestOwnershipStore();
    }

    @Bean
    GuestCredentialService guestCredentialService(
            InMemoryGuestOwnershipStore store,
            SecretProvider secretProvider,
            Clock clock) {
        return new GuestCredentialService(
                store,
                new TokenHasher(secretProvider.get("oauth.client-secret").current()),
                clock);
    }

    @Bean
    GuestGrantService guestGrantService(
            InMemoryGuestOwnershipStore store,
            SecretProvider secretProvider,
            Clock clock) {
        return new GuestGrantService(
                store,
                new TokenHasher(secretProvider.get("oauth.client-secret").current()),
                clock);
    }

    @Bean
    Clock kakaoOAuthClock() {
        return Clock.systemUTC();
    }

    @Bean
    KakaoOAuthClient kakaoOAuthClient(KakaoOAuthProperties properties, SecretProvider secretProvider) {
        return new RestClientKakaoOAuthClient(
                org.springframework.web.client.RestClient.create(),
                properties,
                secretProvider.get("oauth.client-secret").current());
    }
}
