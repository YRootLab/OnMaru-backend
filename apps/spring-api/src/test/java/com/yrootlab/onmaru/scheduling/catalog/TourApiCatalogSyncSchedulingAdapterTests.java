package com.yrootlab.onmaru.scheduling.catalog;

import com.yrootlab.onmaru.tourism.catalog.TourApiCatalogSyncService;
import com.yrootlab.onmaru.tourism.catalog.TourApiClientConfiguration.TourApiSyncSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TourApiCatalogSyncSchedulingAdapterTests {

    @Test
    void skipsApplicationReadySyncWhenBlueGreenStartupSyncIsDisabled() {
        @SuppressWarnings("unchecked")
        ObjectProvider<TourApiCatalogSyncService> service = mock(ObjectProvider.class);
        var scheduler = new TourApiCatalogSyncSchedulingAdapter(
                service,
                new TourApiSyncSettings(null, null, null, null),
                false
        );

        scheduler.initialSync();

        verifyNoInteractions(service);
    }
}
