package com.yrootlab.onmaru.web.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLinkQuery;
import com.yrootlab.onmaru.audio.query.ActiveRevisionOdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.query.OdiiPublicAudioUrlPolicy;
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
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(OdiiStoryConfiguration.OdiiStorySettings.class)
class OdiiStoryConfiguration {

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
            OdiiStorySettings settings) {
        return new ActiveRevisionOdiiStoryQueryStore(revisionStore, settings.dataset());
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
    record OdiiStorySettings(Set<String> publicHosts, String dataset) {

        OdiiStorySettings {
            publicHosts = publicHosts == null ? Set.of() : Set.copyOf(publicHosts);
            dataset = dataset == null || dataset.isBlank() ? "odii-audio" : dataset;
        }
    }
}
