package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.grant.JdbcQueryGrantStore;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrant;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantException;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantService;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantStore;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcPlaceQueryAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

@Configuration
class JourneyQueryToolConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlaceQueryPort.class)
    PlaceQueryPort placeQueryPort(ObjectProvider<DataSource> dataSourceProvider) {
        var dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource != null) {
            return new JdbcPlaceQueryAdapter(dataSource);
        }
        return (regionCode, category, limit) -> List.of();
    }

    /** Without a database the store refuses every grant, so the tool fails closed instead of starting unguarded. */
    @Bean
    QueryGrantStore journeyQueryGrantStore(ObjectProvider<DataSource> dataSourceProvider) {
        var dataSource = dataSourceProvider.getIfAvailable();
        return dataSource != null ? new JdbcQueryGrantStore(dataSource) : new UnavailableQueryGrantStore();
    }

    @Bean
    QueryGrantService queryGrantService(QueryGrantStore store, Clock clock) {
        return new QueryGrantService(store, clock);
    }

    @Bean
    JourneyQueryToolController journeyQueryToolController(
            PlaceQueryPort placeQueryPort, QueryGrantService grants, ObjectProvider<SecretProvider> secrets) {
        SecretProvider provider = secrets.getIfAvailable(() -> name -> {
            throw new IllegalStateException("service secret provider is not configured");
        });
        return new JourneyQueryToolController(placeQueryPort, grants, provider);
    }

    private static final class UnavailableQueryGrantStore implements QueryGrantStore {
        @Override
        public void insert(QueryGrant grant) {
            throw new IllegalStateException("query grant store is not configured");
        }

        @Override
        public <T> T consume(String tokenHash, Instant now, Function<QueryGrant, T> operation) {
            throw new QueryGrantException(QueryGrantException.Code.GRANT_INVALID);
        }
    }
}
