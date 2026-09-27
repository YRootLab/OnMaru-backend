package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("production")
public class CatalogSnapshotPersistenceConfiguration {
    @Bean
    JdbcCatalogPlaceSnapshotStore jdbcCatalogPlaceSnapshotStore(DataSource dataSource) {
        return new JdbcCatalogPlaceSnapshotStore(dataSource);
    }

    @Bean
    JdbcTourApiCatalogPublisher jdbcTourApiCatalogPublisher(DataSource dataSource) {
        return new JdbcTourApiCatalogPublisher(dataSource);
    }

    @Bean
    MapPlaceStore jdbcMapPlaceStore(JdbcCatalogPlaceSnapshotStore store) {
        return store::findPublishedMapSnapshot;
    }

    @Bean
    HanokListStore jdbcHanokListStore(JdbcCatalogPlaceSnapshotStore store) {
        return store::findPublishedHanokSnapshot;
    }
}
