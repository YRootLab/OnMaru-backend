package com.yrootlab.onmaru.web.me.journeythread;

import com.yrootlab.onmaru.journey.thread.InMemoryJourneyThreadStore;
import com.yrootlab.onmaru.journey.thread.JourneyThreadService;
import com.yrootlab.onmaru.journey.thread.JourneyThreadStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class JourneyThreadConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JourneyThreadStore journeyThreadStore() {
        return new InMemoryJourneyThreadStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public JourneyThreadService journeyThreadService(JourneyThreadStore store, Clock clock) {
        return new JourneyThreadService(store, clock);
    }
}
