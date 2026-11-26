package com.yrootlab.onmaru.web.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.exploration.ExplorationActorType;
import com.yrootlab.onmaru.journey.exploration.ExplorationRegionCatalog;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.exploration.ExplorationStore;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationRunDispatcher;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationStore;
import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationRegionCatalog;
import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationStore;
import com.yrootlab.onmaru.journey.actions.JourneyActionService;
import com.yrootlab.onmaru.identity.guest.GuestGrantService;
import com.yrootlab.onmaru.identity.guest.MemberExplorationAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
class ExplorationConfiguration {

    @Bean
    InMemoryExplorationStore inMemoryExplorationStore() {
        return new InMemoryExplorationStore();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "onmaru.journey.exploration.store", havingValue = "jdbc")
    JdbcExplorationStore jdbcExplorationStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcExplorationStore(dataSource, objectMapper);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "onmaru.journey.exploration.store", havingValue = "jdbc")
    JdbcExplorationRegionCatalog jdbcExplorationRegionCatalog(DataSource dataSource, Clock clock) {
        return new JdbcExplorationRegionCatalog(dataSource, clock);
    }

    @Bean
    @ConditionalOnMissingBean(ExplorationRegionCatalog.class)
    ExplorationRegionCatalog fallbackExplorationRegionCatalog() {
        return ExplorationRegionCatalog.defaultFallback();
    }

    @Bean
    InMemoryExplorationRunDispatcher explorationRunDispatcher() {
        return new InMemoryExplorationRunDispatcher();
    }

    @Bean
    InMemoryJourneyRunEventStream journeyRunEventStream(
            @org.springframework.beans.factory.annotation.Value("${onmaru.journey.run-items.enabled:false}")
            boolean runItemsEnabled) {
        return new InMemoryJourneyRunEventStream(runItemsEnabled);
    }

    @Bean
    JourneyActionService journeyActionService(Clock clock) {
        return new JourneyActionService(clock, ignored -> true);
    }

    @Bean
    ExplorationService explorationService(
            ExplorationStore store,
            InMemoryExplorationRunDispatcher dispatcher,
            Clock clock,
            GuestGrantService guestGrantService,
            InMemoryJourneyRunEventStream eventStream,
            JourneyActionService actionService,
            ExplorationRegionCatalog regionCatalog) {
        return new ExplorationService(store, dispatcher, clock, (actor, state) -> {
            if (state.owner().equals(actor)) {
                return true;
            }
            if (actor.type() != ExplorationActorType.MEMBER) {
                return false;
            }
            return guestGrantService.canAccess(new MemberExplorationAccess(
                    java.util.UUID.fromString(actor.subject()),
                    state.id()));
        }, eventStream, actionService, regionCatalog);
    }
}
