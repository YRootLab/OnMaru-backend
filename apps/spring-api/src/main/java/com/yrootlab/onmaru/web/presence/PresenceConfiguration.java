package com.yrootlab.onmaru.web.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(PresenceProperties.class)
class PresenceConfiguration {

    @Bean
    PresenceRoomRegistry presenceRoomRegistry(ObjectMapper objectMapper, Clock clock) {
        return new PresenceRoomRegistry(objectMapper, clock);
    }
}
