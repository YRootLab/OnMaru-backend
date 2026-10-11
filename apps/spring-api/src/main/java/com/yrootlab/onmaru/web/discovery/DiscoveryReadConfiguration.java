package com.yrootlab.onmaru.web.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.persistence.catalog.discovery.JdbcDiscoveryReadStore;
import com.yrootlab.onmaru.journey.saved.place.PlaceSaveEligibility;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name="onmaru.discovery.api.enabled",havingValue="true")
class DiscoveryReadConfiguration {
    @Bean JdbcDiscoveryReadStore discoveryReadStore(DataSource dataSource,ObjectMapper mapper,PlaceSaveEligibility eligibility) {
        return new JdbcDiscoveryReadStore(dataSource,mapper,eligibility);
    }
    @Bean CursorCodec discoveryCursorCodec(ObjectMapper mapper,SecretProvider secrets,Clock clock) {
        return new CursorCodec(mapper,CursorSigningKey.fromUtf8(secrets.get("oauth.client-secret").current()),clock);
    }
}
