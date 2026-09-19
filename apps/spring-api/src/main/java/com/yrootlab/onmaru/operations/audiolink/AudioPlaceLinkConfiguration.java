package com.yrootlab.onmaru.operations.audiolink;

import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkService;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkStore;
import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkLookup;
import com.yrootlab.onmaru.audio.placelink.InMemoryAudioPlaceLinkStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

@Configuration
class AudioPlaceLinkConfiguration {

    @Bean
    @ConditionalOnMissingBean(AudioPlaceLinkStore.class)
    AudioPlaceLinkStore audioPlaceLinkStore() {
        return new InMemoryAudioPlaceLinkStore();
    }

    @Bean
    CanonicalPlaceLinkLookup canonicalPlaceLinkLookup(PlaceDetailQueryService placeDetailQueryService) {
        return new CatalogPlaceLinkLookup(placeDetailQueryService);
    }

    @Bean
    AudioPlaceLinkService audioPlaceLinkService(
            org.springframework.beans.factory.ObjectProvider<AudioPlaceLinkStore> storeProvider,
            CanonicalPlaceLinkLookup canonicalPlaceLinkLookup,
            Clock clock) {
        AudioPlaceLinkStore store = storeProvider.getIfAvailable(InMemoryAudioPlaceLinkStore::new);
        return new AudioPlaceLinkService(store, canonicalPlaceLinkLookup, clock);
    }
}
