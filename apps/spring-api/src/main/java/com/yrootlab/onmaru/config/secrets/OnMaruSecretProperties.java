package com.yrootlab.onmaru.config.secrets;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("onmaru.secrets")
public class OnMaruSecretProperties {

    private Source source = Source.ENVIRONMENT;

    private List<String> requiredNames = new ArrayList<>(List.of(
            "tourapi.service-key",
            "odii.service-key",
            "gemini.api-key",
            "oauth.client-secret",
            "otlp.exporter-token",
            "moderation.operator-token"));

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public List<String> getRequiredNames() {
        return requiredNames;
    }

    public void setRequiredNames(List<String> requiredNames) {
        this.requiredNames = new ArrayList<>(requiredNames);
    }

    public enum Source {
        FAKE,
        ENVIRONMENT
    }
}
