package com.yrootlab.onmaru.web.saved.list;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.query.OdiiSavedStateLookup;
import com.yrootlab.onmaru.audio.query.OdiiStoryNotFoundException;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.saved.odii.InMemorySavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.odii.OdiiStorySaveEligibility;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryService;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;

@Configuration
class SavedResourceConfiguration {

    @Bean
    InMemorySavedOdiiStoryStore savedOdiiStoryStore() {
        return new InMemorySavedOdiiStoryStore();
    }

    @Bean
    OdiiSavedStateLookup odiiSavedStateLookup(InMemorySavedOdiiStoryStore store) {
        return (memberId, storyId) -> memberId.map(id -> store.savedBy(id, storyId)).orElse(false);
    }

    @Bean
    OdiiStorySaveEligibility odiiStorySaveEligibility(OdiiStoryQueryService queryService) {
        return storyId -> {
            try {
                queryService.savedStory(storyId, Optional.empty());
                return true;
            } catch (OdiiStoryNotFoundException exception) {
                return false;
            }
        };
    }

    @Bean
    SavedOdiiStoryService savedOdiiStoryService(
            InMemorySavedOdiiStoryStore store,
            OdiiStorySaveEligibility eligibility,
            Clock clock) {
        return new SavedOdiiStoryService(store, eligibility, clock);
    }

    @Bean
    SavedResourceCursorCodec savedResourceCursorCodec(SecretProvider secrets, Clock clock) {
        var key = CursorSigningKey.fromUtf8(secrets.get("oauth.client-secret").current());
        return new SavedResourceCursorCodec(new CursorCodec(new ObjectMapper(), key, clock), clock);
    }
}
