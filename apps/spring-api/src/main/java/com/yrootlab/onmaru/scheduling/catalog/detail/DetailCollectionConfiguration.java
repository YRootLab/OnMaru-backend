package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.tourism.catalog.TourApiClientConfiguration.TourApiSyncSettings;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * 수집용 키가 설정된 경우에만 상세 수집 작업을 만든다.
 * 키가 없으면 앱은 정상 기동하고 수집만 꺼진다.
 */
@Configuration
@ConditionalOnExpression("'${ONMARU_SECRET_TOURAPI_DETAIL_COLLECTION_KEY_CURRENT:}' != ''")
public class DetailCollectionConfiguration {

    @Bean
    DetailCollectionJob detailCollectionJob(
            DataSource dataSource,
            SecretProvider secrets,
            TourApiSyncSettings settings,
            ObjectProvider<TourApiHttpClient> clients) {
        TourApiHttpClient client = clients.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("TourAPI client is required when detail collection key is set");
        }
        var uris = new TourApiUriBuilder(
                settings.baseUri(),
                secrets.get("tourapi.detail-collection-key").current(),
                settings.mobileApp());
        return new DetailCollectionJob(
                new DetailCollectionPolicy(DetailCollectionPolicy.MAX_DAILY_CAP),
                new JdbcDetailCollectionStore(dataSource),
                new TourApiDetailFetcher(client, uris),
                Clock.systemUTC());
    }
}
