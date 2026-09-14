package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.OAuthLoginService;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties.class)
public class KakaoOAuthConfiguration {

    @Bean
    InMemoryIdentityStore identityStore() {
        return new InMemoryIdentityStore();
    }

    @Bean
    OAuthLoginService oauthLoginService(InMemoryIdentityStore store, SecretProvider secretProvider, Clock clock) {
        return new OAuthLoginService(
                store,
                new TokenHasher(secretProvider.get("oauth.client-secret").current()),
                clock);
    }

    @Bean
    MemberLifecycleService memberLifecycleService(
            InMemoryIdentityStore store,
            SecretProvider secretProvider,
            Clock clock) {
        return new MemberLifecycleService(
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
