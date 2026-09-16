package com.yrootlab.onmaru.web.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLinkQuery;
import com.yrootlab.onmaru.audio.query.ActiveRevisionOdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.query.OdiiPublicAudioUrlPolicy;
import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadataResolver;
import com.yrootlab.onmaru.audio.query.OdiiSavedStateLookup;
import com.yrootlab.onmaru.audio.query.OdiiStoryCursorCodec;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.query.UnavailableOdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.sync.AudioRevisionSnapshot;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.InMemoryAudioRevisionStore;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryStore;
import com.yrootlab.onmaru.observability.TelemetrySink;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(OdiiStoryConfiguration.OdiiStorySettings.class)
public class OdiiStoryConfiguration {

    @Bean
    OdiiPublicAudioUrlPolicy odiiPublicAudioUrlPolicy(OdiiStorySettings settings) {
        return new OdiiPublicAudioUrlPolicy(settings.publicHosts());
    }

    @Bean
    OdiiStoryCursorCodec odiiStoryCursorCodec(
            SecretProvider secretProvider,
            Clock clock) {
        var signingKey = CursorSigningKey.fromUtf8(secretProvider.get("oauth.client-secret").current());
        return new SignedOdiiStoryCursorCodec(new CursorCodec(new ObjectMapper(), signingKey, clock), clock);
    }

    @Bean
    @Profile("!production")
    @ConditionalOnMissingBean(AudioRevisionStore.class)
    AudioRevisionStore odiiAudioRevisionStore(OdiiStorySettings settings) {
        var revisionId = UUID.nameUUIDFromBytes(
                (settings.dataset() + ":bootstrap").getBytes(StandardCharsets.UTF_8));
        return new InMemoryAudioRevisionStore(
                settings.dataset(),
                revisionId,
                AudioRevisionSnapshot.empty(),
                new SourceWatermark("bootstrap", "bootstrap", Instant.EPOCH),
                "odii-bootstrap",
                0);
    }

    @Bean
    OdiiStoryQueryStore odiiStoryQueryStore(
            AudioRevisionStore revisionStore,
            OdiiStorySettings settings,
            OdiiProjectionMetadataResolver metadataResolver,
            ObjectProvider<TelemetrySink> telemetrySinks) {
        var store = new ActiveRevisionOdiiStoryQueryStore(
                revisionStore,
                settings.dataset(),
                metadataResolver);
        return telemetrySinks.getIfAvailable() == null
                ? store
                : new TelemetryOdiiStoryQueryStore(store, telemetrySinks.getObject());
    }

    @Bean
    OdiiProjectionMetadataResolver odiiProjectionMetadataResolver(
            OdiiStorySettings settings,
            ObjectProvider<RegionBoundaryStore> regionStores
    ) {
        return new CatalogRegionOdiiProjectionMetadataResolver(
                settings.category(),
                Optional.ofNullable(regionStores.getIfAvailable()));
    }

    @Bean
    OdiiStoryQueryService odiiStoryQueryService(
            ObjectProvider<OdiiStoryQueryStore> storyQueryStores,
            ObjectProvider<OdiiSavedStateLookup> savedStateLookups,
            ObjectProvider<ApprovedAudioPlaceLinkQuery> approvedPlaceLinkQueries,
            OdiiPublicAudioUrlPolicy audioUrlPolicy,
            OdiiStoryCursorCodec cursorCodec) {
        var storyQueryStore = storyQueryStores.getIfAvailable(UnavailableOdiiStoryQueryStore::new);
        var savedStateLookup = savedStateLookups.getIfAvailable(() -> (memberId, storyId) -> false);
        var approvedPlaceLinkQuery = approvedPlaceLinkQueries.getIfAvailable(
                () -> (spotId, memberId) -> Optional.empty());
        return new OdiiStoryQueryService(
                storyQueryStore,
                savedStateLookup,
                approvedPlaceLinkQuery,
                audioUrlPolicy,
                cursorCodec);
    }

    @ConfigurationProperties("onmaru.audio")
    record OdiiStorySettings(Set<String> publicHosts, String dataset, String category) {

        OdiiStorySettings {
            publicHosts = publicHosts == null ? Set.of() : Set.copyOf(publicHosts);
            dataset = dataset == null || dataset.isBlank() ? "odii-audio" : dataset;
            category = category == null || category.isBlank() ? "오디오 관광" : category;
        }
    }
}
