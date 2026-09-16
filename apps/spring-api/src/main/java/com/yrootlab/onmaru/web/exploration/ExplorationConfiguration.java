package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.exploration.ExplorationActorType;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationRunDispatcher;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationStore;
import com.yrootlab.onmaru.identity.guest.GuestGrantService;
import com.yrootlab.onmaru.identity.guest.MemberExplorationAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ExplorationConfiguration {

    @Bean
    InMemoryExplorationStore explorationStore() {
        return new InMemoryExplorationStore();
    }

    @Bean
    InMemoryExplorationRunDispatcher explorationRunDispatcher() {
        return new InMemoryExplorationRunDispatcher();
    }

    @Bean
    ExplorationService explorationService(
            InMemoryExplorationStore store,
            InMemoryExplorationRunDispatcher dispatcher,
            Clock clock,
            GuestGrantService guestGrantService) {
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
        });
    }
}
