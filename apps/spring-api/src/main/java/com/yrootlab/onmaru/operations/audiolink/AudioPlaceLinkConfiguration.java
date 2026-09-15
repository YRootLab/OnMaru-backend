package com.yrootlab.onmaru.operations.audiolink;

import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkService;
import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkLookup;
import com.yrootlab.onmaru.audio.placelink.InMemoryAudioPlaceLinkStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class AudioPlaceLinkConfiguration {

    @Bean
    InMemoryAudioPlaceLinkStore audioPlaceLinkStore() {
        return new InMemoryAudioPlaceLinkStore();
    }

    @Bean
    CanonicalPlaceLinkLookup canonicalPlaceLinkLookup(PlaceDetailQueryService placeDetailQueryService) {
        return new CatalogPlaceLinkLookup(placeDetailQueryService);
    }

    @Bean
    AudioPlaceLinkService audioPlaceLinkService(
            InMemoryAudioPlaceLinkStore store,
            CanonicalPlaceLinkLookup canonicalPlaceLinkLookup,
            Clock clock) {
        return new AudioPlaceLinkService(store, canonicalPlaceLinkLookup, clock);
    }
}
