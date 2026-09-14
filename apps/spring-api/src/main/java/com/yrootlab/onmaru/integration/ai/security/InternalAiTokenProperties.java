package com.yrootlab.onmaru.integration.ai.security;

import java.time.Duration;

public record InternalAiTokenProperties(
        String issuer,
        String subject,
        String audience,
        String scope,
        Duration timeToLive,
        String secretName) {

    public static InternalAiTokenProperties defaults() {
        return new InternalAiTokenProperties(
                "onmaru-spring",
                "spring-api",
                "onmaru-ai",
                "journey.proposal:write",
                Duration.ofSeconds(60),
                "internal-ai.service-token");
    }
}
